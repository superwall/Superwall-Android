package com.superwall.sdk.paywall.presentation.result.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.operators.TriggerResultResponsePipelineOutput

// Defining the data class equivalent of the struct in Swift
data class TriggerResultPipelineOutput(
    val request: PresentationRequest,
    val triggerResult: TriggerResult,
    val debugInfo: Map<String, Any>
)

// Creating the extension function for Superwall
fun Superwall.checkForPaywallResult(
    triggerResult: TriggerResult,
    debugInfo: Map<String, Any>
): TriggerResultResponsePipelineOutput {
    return when (triggerResult) {
        is TriggerResult.Paywall -> {
            TriggerResultResponsePipelineOutput(
                triggerResult = triggerResult,
                debugInfo = debugInfo,
                confirmableAssignment = null,
                experiment = triggerResult.experiment
            )
        }
        is TriggerResult.Error -> {
            throw PaywallPresentationRequestStatusReason.NoPaywallViewController()
        }
        is TriggerResult.EventNotFound -> {
            throw PaywallPresentationRequestStatusReason.EventNotFound()
        }
        is TriggerResult.Holdout -> {
            throw PaywallPresentationRequestStatusReason.Holdout(triggerResult.experiment)
        }
        is TriggerResult.NoRuleMatch -> {
            throw PaywallPresentationRequestStatusReason.NoRuleMatch()
        }
    }
}
