package com.superwall.sdk.paywall.presentation.internal.operators

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

fun Superwall.logErrors(
        request: PresentationRequest,
        error: Throwable
    ) {
    if (error is PaywallPresentationRequestStatusReason) {
        GlobalScope.launch(Dispatchers.IO) {
            val trackedEvent = InternalSuperwallEvent.PresentationRequest(
                eventData = request.presentationInfo.eventData,
                type = request.flags.type,
                status = PaywallPresentationRequestStatus.NoPresentation,
                statusReason = error
            )
            track(trackedEvent)
        }
    }

    Logger.debug(
        logLevel = LogLevel.info,
        scope = LogScope.paywallPresentation,
        message = "Skipped paywall presentation: ${error.message}, ${error.stackTraceToString()}"
    )
}
