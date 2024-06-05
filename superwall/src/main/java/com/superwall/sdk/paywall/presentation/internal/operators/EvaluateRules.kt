package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.presentation.rule_logic.RuleLogic

// Defining the data class equivalent of the struct in Swift
data class AssignmentPipelineOutput(
    val triggerResult: TriggerResult,
    var confirmableAssignment: ConfirmableAssignment?,
    val debugInfo: Map<String, Any>,
)

suspend fun Superwall.evaluateRules(request: PresentationRequest): RuleEvaluationOutcome {
    val eventData = request.presentationInfo.eventData

    return if (eventData != null) {
        val ruleLogic =
            RuleLogic(
                context = context,
                configManager = dependencyContainer.configManager,
                storage = dependencyContainer.storage,
                factory = dependencyContainer,
            )
        ruleLogic.evaluateRules(event = eventData, triggers = dependencyContainer.configManager.triggersByEventName)
    } else {
        // Called if the debugger is shown.
        val paywallId =
            request.presentationInfo.identifier
                ?: throw PaywallPresentationRequestStatusReason.NoPaywallViewController()

        RuleEvaluationOutcome(triggerResult = InternalTriggerResult.Paywall(Experiment.presentById(paywallId)))
    }
}
