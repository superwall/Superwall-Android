package com.superwall.sdk.paywall.presentation.internal.operators

// File.kt

import com.superwall.sdk.Superwall
import com.superwall.sdk.debug.DebugViewControllerActivity
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.flow.MutableSharedFlow

suspend fun Superwall.checkDebuggerPresentation(
    request: PresentationRequest,
    paywallStatePublisher: MutableSharedFlow<PaywallState>?,
) {
    if (!request.flags.isDebuggerLaunched || request.presenter is DebugViewControllerActivity) {
        return
    }

    val error =
        InternalPresentationLogic.presentationError(
            domain = "SWPresentationError",
            code = 101,
            title = "Debugger Is Presented",
            value = "Trying to present paywall when debugger is launched.",
        )

    val state: PaywallState = PaywallState.PresentationError(error)
    paywallStatePublisher?.emit(state)
    throw PaywallPresentationRequestStatusReason.DebuggerPresented()
}
