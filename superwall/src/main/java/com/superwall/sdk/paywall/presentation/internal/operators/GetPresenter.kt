package com.superwall.sdk.paywall.presentation.internal.operators

import android.app.Activity
import android.app.Presentation
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationPipelineError
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.result.PresentationResult
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (InternalPresentationLogic.userSubscribedAndNotOverridden(
            isUserSubscribed = subscriptionStatus == SubscriptionStatus.ACTIVE,
            overrides = InternalPresentationLogic.UserSubscriptionOverrides(
                isDebuggerLaunched = request.flags.isDebuggerLaunched,
                shouldIgnoreSubscriptionStatus = request.paywallOverrides?.ignoreSubscriptionStatus,
                presentationCondition = paywallViewController.paywall.presentation.condition
            )
        )
    ) {
        paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed()))
        throw PaywallPresentationRequestStatusReason.UserIsSubscribed()
    }

    when (request.flags.type) {
        is PresentationRequestType.GetPaywall -> {
            activateSession(
                request = request,
                paywall = paywallViewController.paywall,
                triggerResult = rulesOutcome.triggerResult
            )
            return null
        }

        is PresentationRequestType.GetImplicitPresentationResult,
        is PresentationRequestType.GetPresentationResult -> return null
        is PresentationRequestType.Presentation -> Unit
        else -> Unit
    }

    activateSession(
        request = request,
        paywall = paywallViewController.paywall,
        triggerResult = rulesOutcome.triggerResult
    )

    return dependencyContainer.activityLifecycleTracker.getCurrentActivity()
}


private fun Superwall.activateSession(
    request: PresentationRequest,
    paywall: Paywall,
    triggerResult: InternalTriggerResult
) {
    val sessionEventsManager = dependencyContainer.sessionEventsManager
    sessionEventsManager?.triggerSession?.activateSession(
        request.presentationInfo,
        request.presenter,
        paywall,
        triggerResult
    )
}
