package com.superwall.sdk.paywall.presentation.internal.operators

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

suspend fun Superwall.waitToPresent(
    request: PresentationRequest,
    dependencyContainer: DependencyContainer? = null
) {
    val container = dependencyContainer ?: this@waitToPresent.dependencyContainer
    val timer = startTimer(request, container)

    val hasIdentity = container.identityManager.hasIdentity
    val hasConfig = container.configManager.hasConfig
    val subscriptionStatus =
        request.flags.subscriptionStatus
            .filter { it != SubscriptionStatus.Unknown }

    hasIdentity.first()
    hasConfig.first()
    subscriptionStatus.first()

    timer?.cancel()
}

private fun Superwall.startTimer(
    request: PresentationRequest,
    dependencyContainer: DependencyContainer
): Job? {
    return if (request.flags.type == PresentationRequestType.GetImplicitPresentationResult) {
        null
    } else {
        CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            val trackedEvent = InternalSuperwallEvent.PresentationRequest(
                eventData = request.presentationInfo.eventData,
                type = request.flags.type,
                status = PaywallPresentationRequestStatus.Timeout,
                statusReason = null
            )
            track(trackedEvent)

            var timeoutReason = ""
            val subscriptionStatus = request.flags.subscriptionStatus.first()
            if (subscriptionStatus == SubscriptionStatus.Unknown) {
                timeoutReason += "\nSuperwall.shared.subscriptionStatus is currently \"unknown\". A paywall cannot show in this state."
            }
            if (dependencyContainer.configManager.config == null) {
                timeoutReason += "\n The config for the user has not returned from the server."
            }

            val hasIdentity = dependencyContainer.identityManager.hasIdentity.filter { it }.first()
            if (!hasIdentity) {
                timeoutReason += "\nThe user's identity has not been set."
            }
            Logger.debug(
                logLevel = LogLevel.info,
                scope = LogScope.paywallPresentation,
                message = "Timeout: Waiting for >5 seconds to continue paywall request. Your paywall may not show because:\n$timeoutReason"
            )
        }
    }
}
