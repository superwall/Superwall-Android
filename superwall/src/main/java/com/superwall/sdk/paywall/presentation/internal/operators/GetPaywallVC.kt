package com.superwall.sdk.paywall.presentation.internal.operators

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal suspend fun Superwall.getPaywallViewController(
    request: PresentationRequest,
    rulesOutcome: RuleEvaluationOutcome,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableStateFlow<PaywallState>? = null,
    dependencyContainer: DependencyContainer? = null
): PaywallViewController {
    val experiment = getExperiment(
        request = request,
        rulesOutcome = rulesOutcome,
        debugInfo = debugInfo,
        paywallStatePublisher = paywallStatePublisher
    )

    val container = dependencyContainer ?: this.dependencyContainer
    val responseIdentifiers = ResponseIdentifiers(
        paywallId = experiment.variant.paywallId,
        experiment = experiment
    )

    var requestRetryCount = 6

    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (subscriptionStatus == SubscriptionStatus.ACTIVE) {
        requestRetryCount = 0
    }

    val paywallRequest = container.makePaywallRequest(
        eventData = request.presentationInfo.eventData,
        responseIdentifiers = responseIdentifiers,
        overrides = PaywallRequest.Overrides(
            products = request.paywallOverrides?.products,
            isFreeTrial = request.presentationInfo.freeTrialOverride
        ),
        isDebuggerLaunched = request.flags.isDebuggerLaunched,
        presentationSourceType = request.presentationSourceType,
        retryCount = requestRetryCount
    )
    return try {
        val isForPresentation = request.flags.type != PresentationRequestType.GetImplicitPresentationResult
                && request.flags.type != PresentationRequestType.GetPresentationResult
        val delegate = request.flags.type.paywallVcDelegateAdapter

        container.paywallManager.getPaywallViewController(
            request = paywallRequest,
            isForPresentation = isForPresentation,
            isPreloading = false,
            delegate = delegate
        )

        container.paywallManager.getPaywallViewController(
            request = paywallRequest,
            isForPresentation = isForPresentation,
            isPreloading = false,
            delegate = delegate
        )
    } catch (e: Exception) {
        if (subscriptionStatus == SubscriptionStatus.ACTIVE) {
            // TODO: throw userIsSubscribed
//            throw userIsSubscribed(paywallStatePublisher)
            throw presentationFailure(e, request, debugInfo, paywallStatePublisher)
        } else {
            throw presentationFailure(e, request, debugInfo, paywallStatePublisher)
        }
    }
}

private suspend fun Superwall.presentationFailure(
    error: Exception,
    request: PresentationRequest,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableStateFlow<PaywallState>?
): Throwable {
    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (InternalPresentationLogic.userSubscribedAndNotOverridden(
            isUserSubscribed = subscriptionStatus == SubscriptionStatus.ACTIVE,
            overrides = InternalPresentationLogic.UserSubscriptionOverrides(
                isDebuggerLaunched = request.flags.isDebuggerLaunched,
                shouldIgnoreSubscriptionStatus = request.paywallOverrides?.ignoreSubscriptionStatus,
                presentationCondition = null
            )
        )
    ) {
        paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed()))
        paywallStatePublisher?.emit(PaywallState.Finalized())
        return PaywallPresentationRequestStatusReason.UserIsSubscribed()
    }

    Logger.debug(
        logLevel = LogLevel.error,
        scope = LogScope.paywallPresentation,
        message = "Error Getting Paywall View Controller",
        info = debugInfo,
        error = error
    )
    paywallStatePublisher?.emit(PaywallState.PresentationError(error))
    paywallStatePublisher?.emit(PaywallState.Finalized())
    return PaywallPresentationRequestStatusReason.NoPaywallViewController()
}
