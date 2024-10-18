package com.superwall.sdk.paywall.presentation.get_paywall

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.misc.Either
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
) {
    @Deprecated("Will be removed in the upcoming versions, use PaywallComponents.view instead")
    val viewController: PaywallView = view
}

@Throws(Throwable::class)
internal suspend fun Superwall.getPaywall(
    request: PresentationRequest,
    publisher: MutableSharedFlow<PaywallState> = MutableSharedFlow(),
): Either<PaywallView, Throwable> =
    getPaywallComponents(request, publisher).fold(onSuccess = {
        it.view.set(
            request = request,
            paywallStatePublisher = publisher,
            unsavedOccurrence = it.rulesOutcome.unsavedOccurrence,
        )
        Either.Success(it.view)
    }, onFailure = {
        logErrors(request, error = it)
        Either.Failure(it)
    })
