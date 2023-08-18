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
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

data class PaywallVcPipelineOutput(
    val triggerResult: TriggerResult,
    val debugInfo: Map<String, Any>,
    val paywallViewController: PaywallViewController,
    val confirmableAssignment: ConfirmableAssignment?
)

suspend fun Superwall.getPaywallViewController(
    request: PresentationRequest,
    input: TriggerResultResponsePipelineOutput,
    paywallStatePublisher: MutableStateFlow<PaywallState>? = null,
    dependencyContainer: DependencyContainer? = null
): PaywallVcPipelineOutput {
    val dependencyContainer = dependencyContainer ?: this.dependencyContainer
    val responseIdentifiers = ResponseIdentifiers(
        paywallId = input.experiment.variant.paywallId,
        experiment = input.experiment
    )
    val paywallRequest = dependencyContainer.makePaywallRequest(
        eventData = request.presentationInfo.eventData,
        responseIdentifiers = responseIdentifiers,
        overrides = PaywallRequest.Overrides(
            products = request.paywallOverrides?.products,
            isFreeTrial = request.presentationInfo.freeTrialOverride
        ),
        isDebuggerLaunched = request.flags.isDebuggerLaunched
    )
    println("!!paywallRequest: $paywallRequest")
//    try {
    val delegate = request.flags.type.paywallVcDelegateAdapter
    println("!!delegate: $delegate")
    val paywallViewController = dependencyContainer.paywallManager.getPaywallViewController(
        request = paywallRequest,
        presentationRequest = request,
        isPreloading = false,
        delegate = delegate
    )
    println("!!paywallViewController: $paywallViewController")

    val output = PaywallVcPipelineOutput(
        triggerResult = input.triggerResult,
        debugInfo = input.debugInfo,
        paywallViewController = paywallViewController,
        confirmableAssignment = input.confirmableAssignment
    )
    return output
//    } catch (error: Exception) {
//        println("!!Error: $error")
//        when (request.flags.type) {
//            is PresentationRequestType.GetImplicitPresentationResult,
//            is PresentationRequestType.GetPresentationResult -> throw PaywallPresentationRequestStatusReason.NoPaywallViewController()
//            is PresentationRequestType.Presentation,
//            is PresentationRequestType.GetPaywallViewController -> {
//                paywallStatePublisher?.let {
//                    throw presentationFailure(error, request, input.debugInfo, it)
//                } ?: throw error // Will never get here
//            }
//            else -> {
//                // Will never get here
//                throw error
//            }
//        }
//    }
}

private suspend fun Superwall.presentationFailure(
    error: Exception,
    request: PresentationRequest,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableStateFlow<PaywallState>
): Throwable {
    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (InternalPresentationLogic.userSubscribedAndNotOverridden(
            isUserSubscribed = subscriptionStatus == SubscriptionStatus.Active,
            overrides = InternalPresentationLogic.UserSubscriptionOverrides(
                isDebuggerLaunched = request.flags.isDebuggerLaunched,
                shouldIgnoreSubscriptionStatus = request.paywallOverrides?.ignoreSubscriptionStatus,
                presentationCondition = null
            )
        )
    ) {
        paywallStatePublisher.emit(PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed()))
        paywallStatePublisher.emit(PaywallState.Finalized())
        return PaywallPresentationRequestStatusReason.UserIsSubscribed()
    }

    Logger.debug(
        logLevel = LogLevel.error,
        scope = LogScope.paywallPresentation,
        message = "Error Getting Paywall View Controller",
        info = debugInfo,
        error = error
    )
    paywallStatePublisher.emit(PaywallState.PresentationError(error))
    paywallStatePublisher.emit(PaywallState.Finalized())
    return PaywallPresentationRequestStatusReason.NoPaywallViewController()
}
