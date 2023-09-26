package com.superwall.sdk.paywall.presentation.get_presentation_result

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationPipelineError
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.getPaywallComponents
import com.superwall.sdk.paywall.presentation.result.PresentationResult


internal suspend fun Superwall.getPresentationResult(request: PresentationRequest): PresentationResult {
    return try {
        val paywallComponents = getPaywallComponents(request)
        val triggerResult = paywallComponents.rulesOutcome.triggerResult
        val presentationResult = GetPresentationResultLogic.convertTriggerResult(triggerResult)
        presentationResult
    } catch (error: PresentationPipelineError) {
        handle(error, request.flags.type)
    }
}

private fun handle(
    error: PresentationPipelineError,
    requestType: PresentationRequestType
): PresentationResult {
    if (requestType != PresentationRequestType.GetImplicitPresentationResult) {
        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.paywallPresentation,
            message = "Paywall presentation error: $error"
        )
    }

    return when (error) {
        is PaywallPresentationRequestStatusReason.UserIsSubscribed -> PresentationResult.UserIsSubscribed()
        is PaywallPresentationRequestStatusReason.NoPaywallViewController -> PresentationResult.PaywallNotAvailable()
        is PaywallPresentationRequestStatusReason.NoRuleMatch -> PresentationResult.NoRuleMatch()
        is PaywallPresentationRequestStatusReason.Holdout -> PresentationResult.Holdout(error.experiment)
        is PaywallPresentationRequestStatusReason.EventNotFound -> PresentationResult.EventNotFound()
        is PaywallPresentationRequestStatusReason.DebuggerPresented,
        is PaywallPresentationRequestStatusReason.NoPresenter,
        is PaywallPresentationRequestStatusReason.PaywallAlreadyPresented,
        is PaywallPresentationRequestStatusReason.NoConfig,
        is PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout -> PresentationResult.PaywallNotAvailable()
    }
}