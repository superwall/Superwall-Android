package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.rule_logic.RuleLogic

// Defining the data class equivalent of the struct in Swift
data class AssignmentPipelineOutput(
    val triggerResult: TriggerResult,
    var confirmableAssignment: ConfirmableAssignment?,
    val debugInfo: Map<String, Any>
)

// Creating the extension function for Superwall
suspend fun Superwall.evaluateRules(
    request: PresentationRequest,
    debugInfo: Map<String, Any>
): AssignmentPipelineOutput {
    val eventData = request.presentationInfo.eventData
    return if (eventData != null) {
        val assignmentLogic = RuleLogic(
            context = contex,
            configManager = dependencyContainer.configManager,
            storage = dependencyContainer.storage,
            factory = dependencyContainer
        )
        val eventOutcome = assignmentLogic.evaluateRules(
            event = eventData,
            triggers = dependencyContainer.configManager.triggersByEventName,
            isPreemptive = request.flags.type == PresentationRequestType.GetPresentationResult
        )
        val confirmableAssignment = eventOutcome.confirmableAssignment

        AssignmentPipelineOutput(
            triggerResult = eventOutcome.triggerResult,
            confirmableAssignment = confirmableAssignment,
            debugInfo = debugInfo
        )
    } else {
        // Called if the debugger is shown.
        val paywallId = request.presentationInfo.identifier
        if (paywallId == null) {
            // This error will never be thrown. Just preferring this
            // to force unwrapping.
            throw PaywallPresentationRequestStatusReason.NoPaywallViewController()
        }
        AssignmentPipelineOutput(
            triggerResult = TriggerResult.Paywall(Experiment.presentById(paywallId)),
            debugInfo = debugInfo,
            confirmableAssignment = null
        )
    }
}
