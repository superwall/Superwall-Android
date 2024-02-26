package com.superwall.sdk.paywall.presentation.internal.operators

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableSharedFlow
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
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null
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
            val sessionId = activateSession(
                request = request,
                triggerResult = rulesOutcome.triggerResult
            )
            paywallViewController.paywall.triggerSessionId = sessionId
            return null
        }

        is PresentationRequestType.GetImplicitPresentationResult,
        is PresentationRequestType.GetPresentationResult -> return null
        is PresentationRequestType.Presentation -> Unit
        else -> Unit
    }

    val sessionId = activateSession(
        request = request,
        triggerResult = rulesOutcome.triggerResult
    )
    paywallViewController.paywall.triggerSessionId = sessionId

    val currentActivity = dependencyContainer.activityProvider?.getCurrentActivity()

    if (currentActivity == null) {
        Logger.debug(
            logLevel = LogLevel.error,
            scope = LogScope.paywallPresentation,
            message = "Current Activity is null, can't present paywall"
        )
        val error = InternalPresentationLogic.presentationError(
            domain = "SWPresentationError",
            code = 103,
            title = "No Activity to present paywall on",
            value = "This usually happens when you call this method before a window was made key and visible."
        )
        val state = PaywallState.PresentationError(error)
        paywallStatePublisher?.emit(state)
        throw PaywallPresentationRequestStatusReason.NoPresenter()
    }
    return currentActivity
}


private suspend fun Superwall.activateSession(
    request: PresentationRequest,
    triggerResult: InternalTriggerResult
): String? {
    val sessionEventsManager = dependencyContainer.sessionEventsManager
    return sessionEventsManager?.triggerSession?.activateSession(
        request.presentationInfo,
        triggerResult
    )
}
