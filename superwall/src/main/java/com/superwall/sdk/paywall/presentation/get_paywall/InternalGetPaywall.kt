package com.superwall.sdk.paywall.presentation.get_paywall

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.getPaywallComponents
import com.superwall.sdk.paywall.presentation.internal.operators.logErrors
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal data class PaywallComponents(
    val viewController: PaywallViewController,
    val presenter: Activity?,
    val rulesOutcome: RuleEvaluationOutcome,
    val debugInfo: Map<String, Any>
)

@Throws(Throwable::class)
internal suspend fun Superwall.getPaywall(
    request: PresentationRequest,
    publisher: MutableSharedFlow<PaywallState> = MutableSharedFlow()
): PaywallViewController {
    return try {
        val paywallComponents = getPaywallComponents(request, publisher)

        paywallComponents.viewController.set(
            request = request,
            paywallStatePublisher = publisher,
            unsavedOccurrence = paywallComponents.rulesOutcome.unsavedOccurrence
        )
        paywallComponents.viewController
    } catch (error: Throwable) {
        logErrors(request, error = error)
        // TODO: throw the proper error
//        throw mapError(error, toObjc = toObjc)
        throw error
    }
}
