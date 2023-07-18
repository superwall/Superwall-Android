package com.superwall.sdk.paywall.presentation.internal

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.operators.*
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.Error

typealias PresentationSubject = MutableStateFlow<PresentationRequest?>
typealias PaywallStatePublisher = Flow<PaywallState>
typealias PresentablePipelineOutputPublisher = Flow<PresentablePipelineOutput>

// I'm assuming that there are appropriate definitions for the functions used in `internallyPresent` that are not provided in the Swift code.
fun Superwall.internallyPresent(request: PresentationRequest, publisher: MutableStateFlow<PaywallState> = MutableStateFlow(PaywallState.NotStarted())): PaywallStatePublisher {
    GlobalScope.launch {
        try {
            checkNoPaywallAlreadyPresented(request, publisher)
            waitToPresent(request)
            val debugInfo = logPresentation(request, "Called Superwall.instance.register")
            checkDebuggerPresentation(request, publisher)
            val assignmentOutput = evaluateRules(request, debugInfo)
            checkUserSubscription(request, assignmentOutput.triggerResult, publisher)
            confirmHoldoutAssignment(assignmentOutput)
            val triggerResultOutput = handleTriggerResult(request, assignmentOutput, publisher)
            val paywallVcOutput = getPaywallViewController(request, triggerResultOutput, publisher)

            val presentableOutput = checkPaywallIsPresentable(paywallVcOutput, request, publisher)
            confirmPaywallAssignment(request, presentableOutput)
            presentPaywall(request, presentableOutput, publisher)
        } catch (e: Exception) {
            logErrors(request, e)
        }
    }

    return publisher
}

// Note that there's no direct equivalent for the @MainActor attribute in Swift, but Dispatchers.Main in coroutines serves a similar purpose.
fun Superwall.dismiss(paywallViewController: PaywallViewController, result: PaywallResult, closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic, completion: (() -> Unit)? = null) {
    GlobalScope.launch(Dispatchers.Main) {
        paywallViewController.dismiss(result, closeReason)
        completion?.invoke()
    }
}

fun Superwall.dismiss() {
    if (paywallViewController != null) {
        dismiss(paywallViewController!!, PaywallResult.Declined())
    }
}

fun Superwall.dismissForNextPaywall() {
    if (paywallViewController != null) {
        dismiss(paywallViewController!!, PaywallResult.Declined(), PaywallCloseReason.ForNextPaywall)
    }
}