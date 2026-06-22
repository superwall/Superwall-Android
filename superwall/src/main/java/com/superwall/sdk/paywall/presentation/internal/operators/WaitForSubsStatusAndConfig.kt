package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Waits for [ConfigState.Retrieved], retrying once after an initial 1-second window.
 * Throws immediately on [ConfigState.Failed] or when all retries are exhausted.
 *
 * @param retriesLeft remaining retry attempts; each attempt uses a progressively
 *   longer timeout (1s per non-final attempt, 5s for the final one).
 */
internal suspend fun StateFlow<ConfigState>.configOrThrow(retriesLeft: Int = 1) {
    try {
        withTimeout(if (retriesLeft > 0) 1.seconds else 5.seconds) {
            first { result ->
                if (result is ConfigState.Failed) throw result.throwable
                result is ConfigState.Retrieved
            }
        }
    } catch (e: TimeoutCancellationException) {
        if (retriesLeft > 0) configOrThrow(retriesLeft - 1) else throw e
    }
}

internal suspend fun waitForEntitlementsAndConfig(
    request: PresentationRequest,
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null,
    dependencyContainer: DependencyContainer? = null,
) {
    @Suppress("NAME_SHADOWING")
    val dependencyContainer = dependencyContainer ?: Superwall.instance.dependencyContainer

    try {
        withTimeout(5.seconds) {
            request.flags.entitlements
                .filter { it !is SubscriptionStatus.Unknown }
                .first()
        }
    } catch (e: TimeoutCancellationException) {
        // Handle exception, cancel the task, and log timeout and fail the request
        dependencyContainer.ioScope().launch {
            val trackedEvent =
                InternalSuperwallEvent.PresentationRequest(
                    eventData = request.presentationInfo.eventData,
                    type = request.flags.type,
                    status = PaywallPresentationRequestStatus.Timeout,
                    statusReason = PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout(),
                    factory = dependencyContainer,
                )
            dependencyContainer.track(trackedEvent)
        }
        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.paywallPresentation,
            message =
                "Timeout: Superwall.instance.entitlement.status has been \"unknown\" for " +
                    "over 5 seconds resulting in a failure.",
        )
        val error =
            InternalPresentationLogic.presentationError(
                domain = "SWKPresentationError",
                code = 105,
                title = "Timeout",
                value = "The entitlement status failed to change from \"unknown\".",
            )
        paywallStatePublisher?.emit(PaywallState.PresentationError(error))
        throw PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout()
    }

    val configState = dependencyContainer.configManager.configState

    // In-flight states get one retry (1s initial window, then 5s fallback).
    // Already-resolved states (Retrieved/Failed) complete on the first attempt.
    val retries =
        if (configState.value is ConfigState.Retrieving ||
            configState.value is ConfigState.Retrying ||
            configState.value is ConfigState.None
        ) {
            1
        } else {
            0
        }

    try {
        configState.configOrThrow(retries)
    } catch (e: Throwable) {
        e.printStackTrace()
        // Only track when config timed out — a Failed state is an immediate error, not a timeout.
        if (e is TimeoutCancellationException) {
            dependencyContainer.ioScope().launch {
                val trackedEvent =
                    InternalSuperwallEvent.PresentationRequest(
                        eventData = request.presentationInfo.eventData,
                        type = request.flags.type,
                        status = PaywallPresentationRequestStatus.Timeout,
                        statusReason = PaywallPresentationRequestStatusReason.NoConfig(),
                        factory = dependencyContainer,
                    )
                dependencyContainer.track(trackedEvent)
            }
        }
        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.paywallPresentation,
            message = "Timeout: The config could not be retrieved in a reasonable time.",
        )
        val errorValue =
            if (e is TimeoutCancellationException) {
                "Trying to present paywall without the Superwall config."
            } else {
                "Trying to present paywall without the Superwall config. Error: ${e.message}"
            }
        paywallStatePublisher?.emit(
            PaywallState.PresentationError(
                InternalPresentationLogic.presentationError(
                    domain = "SWKPresentationError",
                    code = 104,
                    title = "No Config",
                    value = errorValue,
                ),
            ),
        )
        throw PaywallPresentationRequestStatusReason.NoConfig()
    }

    // Defense in depth: if a Pending identity item (Seed / Assignments / etc.)
    // never resolves — e.g. because an upstream coroutine got orphaned or
    // the underlying flow never emits — don't let paywall presentation hang
    // forever. Falls through after the timeout; the paywall presents with
    // whatever identity state is current.
    val identityResolved =
        withTimeoutOrNull(5.seconds) {
            dependencyContainer.identityManager.awaitLatestIdentity()
        }
    if (identityResolved == null) {
        Logger.debug(
            logLevel = LogLevel.warn,
            scope = LogScope.paywallPresentation,
            message =
                "Timeout: identity did not become ready within 5s. " +
                    "Proceeding with current identity state.",
        )
    }
}
