package com.superwall.sdk.paywall.presentation.internal.operators

import LogLevel
import LogScope
import Logger
import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

data class PresentablePipelineOutput(
    val debugInfo: Map<String, Any>,
    val paywallViewController: PaywallViewController,
    val presenter: Activity,
    val confirmableAssignment: ConfirmableAssignment?
)



suspend fun Superwall.checkPaywallIsPresentable(
        input: PaywallVcPipelineOutput,
        request: PresentationRequest,
        paywallStatePublisher: MutableStateFlow<PaywallState>? = null
    ): PresentablePipelineOutput {
    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (InternalPresentationLogic.userSubscribedAndNotOverridden(
            isUserSubscribed = subscriptionStatus == SubscriptionStatus.Active,
            overrides = InternalPresentationLogic.UserSubscriptionOverrides(
                isDebuggerLaunched = request.flags.isDebuggerLaunched,
                shouldIgnoreSubscriptionStatus = request.paywallOverrides?.ignoreSubscriptionStatus,
                presentationCondition = input.paywallViewController.paywall.presentation.condition
            )
        )
    ) {
        val state: PaywallState = PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed())
        paywallStatePublisher?.value = state
        throw PaywallPresentationRequestStatusReason.UserIsSubscribed()
    }

    // Return early with stub if we're just getting the paywall result.
    if (request.flags.type == PresentationRequestType.GetPresentationResult ||
        request.flags.type == PresentationRequestType.GetImplicitPresentationResult
    ) {
        return PresentablePipelineOutput(
            debugInfo = input.debugInfo,
            paywallViewController = input.paywallViewController,
            presenter = Activity(),
            confirmableAssignment = input.confirmableAssignment
        )
    }

//        if (request.presenter == null) {
//            createPresentingWindowIfNeeded()
//        }

    // Make sure there's a presenter. If there isn't throw an error if no paywall is being presented
    val providedActivity = request.presenter

    var presenter: Activity? = providedActivity
    if (presenter == null) {
        presenter = dependencyContainer.activityLifecycleTracker.getCurrentActivity()
    }

    if (presenter == null) {
        Logger.debug(
            logLevel = LogLevel.error,
            scope = LogScope.paywallPresentation,
            message = "No Presenter To Present Paywall",
            info = input.debugInfo,
            error = null
        )

        val error = InternalPresentationLogic.presentationError(
            domain = "SWPresentationError",
            code = 103,
            title = "No Activity to present paywall on",
            value = "This usually happens when you call this method before a window was made key and visible."
        )
        val state: PaywallState = PaywallState.PresentationError(error)
        paywallStatePublisher?.emit(state)
        paywallStatePublisher?.emit(PaywallState.Finalized())
        throw PaywallPresentationRequestStatusReason.NoPresenter()
    }

    val sessionEventsManager = dependencyContainer.sessionEventsManager
    sessionEventsManager?.triggerSession?.activateSession(
        forPresentationInfo = request.presentationInfo,
        on = request.presenter,
        paywall = input.paywallViewController.paywall,
        triggerResult = input.triggerResult
    )

    return PresentablePipelineOutput(
        debugInfo = input.debugInfo,
        paywallViewController = input.paywallViewController,
        presenter = presenter,
        confirmableAssignment = input.confirmableAssignment
    )
}

//    suspend fun createPresentingWindowIfNeeded() {
//        if (presentationItems.window != null) {
//            return
//        }
//        val activeWindow = App.instance.activeWindow
//        var presentingWindow: Window? = null
//
//        val windowScene = activeWindow?.windowScene
//        if (windowScene != null) {
//            presentingWindow = Window(windowScene)
//        }
//
//        presentingWindow?.rootActivity = Activity()
//        presentationItems.window = presentingWindow
//    }
//
//    fun destroyPresentingWindow() {
//        presentationItems.window = null
//    }
//}
