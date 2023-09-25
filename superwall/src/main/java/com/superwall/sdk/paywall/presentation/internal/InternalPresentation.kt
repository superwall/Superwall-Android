package com.superwall.sdk.paywall.presentation.internal

import android.util.Log
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.operators.*
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

typealias PresentationSubject = MutableStateFlow<PresentationRequest?>
typealias PaywallStatePublisher = Flow<PaywallState>
typealias PresentablePipelineOutputPublisher = Flow<PresentablePipelineOutput>

fun Superwall.internallyPresent(
    request: PresentationRequest,
    publisher: MutableStateFlow<PaywallState> = MutableStateFlow(
        PaywallState.NotStarted()
    )
): PaywallStatePublisher {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            checkNoPaywallAlreadyPresented(request, publisher)

            // Print a log here to indicate that the paywall is being presented.
            val paywallComponents = getPaywallComponents(request, publisher)

            // TODO: Guard
            val presenter = paywallComponents.presenter!!
//            guard let presenter = paywallComponents.presenter else {
//                // Will never get here as an error would have already been thrown.
//                return
//            }

            presentPaywallViewController(
                paywallViewController = paywallComponents.viewController,
                presenter = presenter,
                unsavedOccurrence = paywallComponents.rulesOutcome.unsavedOccurrence,
                debugInfo = paywallComponents.debugInfo,
                request = request,
                paywallStatePublisher = publisher
            )
        } catch (e: Throwable) {
            logErrors(request, e)
        }
    }

    return publisher
}

// Note that there's no direct equivalent for the @MainActor attribute in Swift, but Dispatchers.Main in coroutines serves a similar purpose.
fun Superwall.dismiss(
    paywallViewController: PaywallViewController,
    result: PaywallResult,
    closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic,
    completion: (() -> Unit)? = null
) {
    GlobalScope.launch(Dispatchers.Main) {
        paywallViewController.dismiss(result, closeReason)
        completion?.invoke()
    }
}

suspend fun Superwall.dismiss() {
    if (paywallViewController != null) {
        dismiss(paywallViewController!!, PaywallResult.Declined())
    }
}

suspend fun Superwall.dismissForNextPaywall() {
    if (paywallViewController != null) {
        dismiss(
            paywallViewController!!,
            PaywallResult.Declined(),
            PaywallCloseReason.ForNextPaywall
        )
    }
}