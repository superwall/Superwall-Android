package com.superwall.sdk.paywall.presentation.internal.operators

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.PaywallViewPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Presents the paywall view controller, stores the presentation request for future use,
 * and sends back a `presented` state to the paywall state publisher.
 *
 * - Parameters:
 *   - paywallStatePublisher: A `MutableStateFlow` that gets sent `PaywallState` objects.
 */
suspend fun Superwall.presentPaywall(
    request: PresentationRequest,
    input: PresentablePipelineOutput,
    paywallStatePublisher: MutableStateFlow<PaywallState>
) {
    GlobalScope.launch(Dispatchers.Main) {
        val trackedEvent = InternalSuperwallEvent.PresentationRequest(
            eventData = request.presentationInfo.eventData,
            type = request.flags.type,
            status = PaywallPresentationRequestStatus.Presentation,
            statusReason = null
        )
        track(trackedEvent)
    }

    val paywallViewPresenter = PaywallViewPresenter(
        activity = input.presenter,
        paywallViewController = input.paywallViewController
    )

    val isPresented = paywallViewPresenter.present(
        presentationStyleOverride = request.paywallOverrides?.presentationStyle,
    ) { canPresent ->
        println("!! canPresent: $canPresent")
        if (canPresent) {
            val state: PaywallState =
                PaywallState.Presented(input.paywallViewController.paywallInfo!!)
            paywallStatePublisher.value = state
        } else {
            Logger.debug(
                logLevel = LogLevel.info,
                scope = LogScope.paywallPresentation,
                message = "Paywall Already Presented",
                info = input.debugInfo
            )
            val error = InternalPresentationLogic.presentationError(
                domain = "SWPresentationError",
                code = 102,
                title = "Paywall Already Presented",
                value = "Trying to present paywall while another paywall is presented."
            )

            GlobalScope.launch {
                paywallStatePublisher.emit(PaywallState.PresentationError(error))
                paywallStatePublisher.emit(PaywallState.Finalized())
            }
            throw PaywallPresentationRequestStatusReason.PaywallAlreadyPresented()
        }
    }
}
