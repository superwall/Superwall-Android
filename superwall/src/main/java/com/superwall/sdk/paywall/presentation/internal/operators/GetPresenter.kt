package com.superwall.sdk.paywall.presentation.internal.operators

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableStateFlow

data class PresentablePipelineOutput(
    val debugInfo: Map<String, Any>,
    val paywallViewController: PaywallViewController,
    val presenter: Activity,
    val confirmableAssignment: ConfirmableAssignment?
)

suspend fun Superwall.getPresenterIfNecessary(
    paywallViewController: PaywallViewController,
    rulesOutcome: RuleEvaluationOutcome,
    request: PresentationRequest,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableStateFlow<PaywallState>? = null
): Activity? {
    // TODO: Implement
    return dependencyContainer.activityLifecycleTracker.getCurrentActivity()
}
