package com.superwall.sdk.paywall.presentation.get_paywall

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.getPaywallComponents
import com.superwall.sdk.paywall.presentation.internal.operators.logErrors
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.flow.MutableSharedFlow

data class PaywallComponents(
    val view: PaywallView,
    val presenter: Activity?,
    val rulesOutcome: RuleEvaluationOutcome,
    val debugInfo: Map<String, Any>,
){
    @Deprecated("Will be removed in the upcoming versions, use PaywallComponents.view instead")
    val viewController: PaywallView = view
}

@Throws(Throwable::class)
suspend fun Superwall.getPaywall(
    request: PresentationRequest,
    publisher: MutableSharedFlow<PaywallState> = MutableSharedFlow(),
): PaywallView =
    try {
        val paywallComponents = getPaywallComponents(request, publisher)

        paywallComponents.viewController.set(
            request = request,
            paywallStatePublisher = publisher,
            unsavedOccurrence = paywallComponents.rulesOutcome.unsavedOccurrence,
        )
        paywallComponents.viewController
    } catch (error: Throwable) {
        logErrors(request, error = error)
        // TODO: throw the proper error
//        throw mapError(error, toObjc = toObjc)
        throw error
    }
