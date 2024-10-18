package com.superwall.sdk.paywall.presentation.internal

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.operators.*
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

typealias PaywallStatePublisher = Flow<PaywallState>

@Throws(Throwable::class)
suspend fun Superwall.internallyPresent(
    request: PresentationRequest,
    publisher: MutableSharedFlow<PaywallState>,
) {
    try {
        checkNoPaywallAlreadyPresented(request, publisher)

        // Print a log here to indicate that the paywall is being presented.
        val paywallComponents = getPaywallComponents(request, publisher).getOrThrow()

        val presenter =
            requireNotNull(paywallComponents.presenter) {
                "Presenter must not be null"
            }

        val paywallView = paywallComponents.view

        // Note: Deviation from iOS. Unique to Android. This is also done in `PublicGetPaywall.kt`.
        // See comments there.
        paywallView.prepareToDisplay()

        presentPaywallView(
            paywallView = paywallView,
            presenter = presenter,
            unsavedOccurrence = paywallComponents.rulesOutcome.unsavedOccurrence,
            debugInfo = paywallComponents.debugInfo,
            request = request,
            paywallStatePublisher = publisher,
        )
    } catch (e: Throwable) {
        logErrors(request, e)
    }
}

suspend fun Superwall.dismiss(
    paywallView: PaywallView,
    result: PaywallResult,
    closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic,
    completion: (() -> Unit)? = null,
) = withContext(Dispatchers.Main) {
    paywallView.dismiss(result, closeReason, completion)
}
