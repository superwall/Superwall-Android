package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.internal.userIsSubscribed
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

internal suspend fun Superwall.waitForSubsStatusAndConfig(
    request: PresentationRequest,
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null,
    dependencyContainer: DependencyContainer? = null,
) {
    @Suppress("NAME_SHADOWING")
    val dependencyContainer = dependencyContainer ?: this.dependencyContainer

    try {
        withTimeout(5.seconds) {
            request.flags.subscriptionStatus
                .filter { it != SubscriptionStatus.UNKNOWN }
                .first()
        }
    } catch (e: TimeoutCancellationException) {
        // Handle exception, cancel the task, and log timeout and fail the request
        ioScope.launch {
            val trackedEvent =
                InternalSuperwallEvent.PresentationRequest(
                    eventData = request.presentationInfo.eventData,
                    type = request.flags.type,
                    status = PaywallPresentationRequestStatus.Timeout,
                    statusReason = PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout(),
                    factory = dependencyContainer,
                )
            track(trackedEvent)
        }
        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.paywallPresentation,
            message =
                "Timeout: Superwall.instance.subscriptionStatus has been \"unknown\" for " +
                    "over 5 seconds resulting in a failure.",
        )
        val error =
            InternalPresentationLogic.presentationError(
                domain = "SWKPresentationError",
                code = 105,
                title = "Timeout",
                value = "The subscription status failed to change from \"unknown\".",
            )
        paywallStatePublisher?.emit(PaywallState.PresentationError(error))
        throw PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout()
    }

    val configState = dependencyContainer.configManager.configState

    suspend fun StateFlow<ConfigState>.configOrThrow() {
        first { result ->
            if (result is ConfigState.Failed) throw result.throwable
            result is ConfigState.Retrieved
        }
    }

    val subscriptionIsActive = subscriptionStatus.value == SubscriptionStatus.ACTIVE
    when {
        // Config is still retrieving, wait for <=1 second.
        // At 1s we cancel the task and check config again.
        subscriptionIsActive &&
            configState.value is ConfigState.Retrieving -> {
            try {
                withTimeout(1.seconds) {
                    configState
                        .configOrThrow()
                }
            } catch (e: TimeoutCancellationException) {
                ioScope.launch {
                    val trackedEvent =
                        InternalSuperwallEvent.PresentationRequest(
                            eventData = request.presentationInfo.eventData,
                            type = request.flags.type,
                            status = PaywallPresentationRequestStatus.Timeout,
                            statusReason = PaywallPresentationRequestStatusReason.NoConfig(),
                            factory = dependencyContainer,
                        )
                    track(trackedEvent)
                }
                Logger.debug(
                    logLevel = LogLevel.info,
                    scope = LogScope.paywallPresentation,
                    message = "Timeout: The config could not be retrieved in a reasonable time for a subscribed user.",
                )
                throw userIsSubscribed(paywallStatePublisher)
            }
        }
        // If the user is subscribed and there's no config (for whatever reason),
        // just call the feature block.
        subscriptionIsActive &&
            configState.value.getConfig() == null &&
            configState.value !is ConfigState.Retrieving -> {
            throw userIsSubscribed(paywallStatePublisher)
        }

        subscriptionIsActive &&
            configState.value.getConfig() != null -> {
            // If the user is subscribed and there is config, continue.
        }

        // User is not subscribed, so we either wait for config and show the paywall
        // Or we show the paywall without config.
        else -> {
            try {
                configState.configOrThrow()
            } catch (e: Throwable) {
                // If config completely dies, then throw an error
                val error =
                    InternalPresentationLogic.presentationError(
                        domain = "SWKPresentationError",
                        code = 104,
                        title = "No Config",
                        value = "Trying to present paywall without the Superwall config.",
                    )
                val state = PaywallState.PresentationError(error)
                paywallStatePublisher?.emit(state)
                throw PaywallPresentationRequestStatusReason.NoConfig()
            }
        }
    }

// Get the identity. This may or may not wait depending on whether the dev
// specifically wants to wait for assignments.
    dependencyContainer.identityManager.hasIdentity.first()
}
