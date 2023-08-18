package com.superwall.sdk.paywall.presentation.internal.operators

// File.kt

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.flow.MutableStateFlow

suspend  fun Superwall.checkDebuggerPresentation(
    request: PresentationRequest,
    paywallStatePublisher: MutableStateFlow<PaywallState>
) {

    // TODO: (Debugger) Fix this
//    if (!request.flags.isDebuggerLaunched || request.presenter is DebugViewController) {
    if (!request.flags.isDebuggerLaunched) {
        return
    }

    val error = InternalPresentationLogic.presentationError(
        domain = "SWPresentationError",
        code = 101,
        title = "Debugger Is Presented",
        value = "Trying to present paywall when debugger is launched."
    )

    val state: PaywallState = PaywallState.PresentationError(error)

    paywallStatePublisher.emit(state)
    paywallStatePublisher.emit(PaywallState.Finalized())
    throw PaywallPresentationRequestStatusReason.DebuggerPresented()
}
