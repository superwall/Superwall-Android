package com.superwall.sdk.paywall.presentation.rule_logic

import android.content.Context
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluator
import com.superwall.sdk.storage.Storage

//data class ConfirmableAssignment(val experimentId: Experiment.ID, val variant: Experiment.Variant)

data class Outcome(
    var confirmableAssignment: ConfirmableAssignment? = null,
    var triggerResult: TriggerResult
)

class RuleLogic(
    private val context: Context,
    private val configManager: ConfigManager,
    private val storage: Storage,
    private val factory: RuleAttributesFactory
) {
    suspend fun evaluateRules(
        event: EventData,
        triggers: Map<String, Trigger>,
        isPreemptive: Boolean
    ): Outcome {
        val trigger = triggers[event.name]
        return if (trigger == null) {
            Outcome(triggerResult = TriggerResult.EventNotFound)
        } else {
            val rule = findMatchingRule(event, trigger, isPreemptive)

            if (rule == null) {
                Outcome(triggerResult = TriggerResult.NoRuleMatch)
            } else {
                val variant: Experiment.Variant
                var confirmableAssignment: ConfirmableAssignment? = null

                val confirmedAssignments = storage.getConfirmedAssignments()
                variant = confirmedAssignments[rule.experiment.id]
                    ?: configManager.unconfirmedAssignments[rule.experiment.id]
                            ?: throw PaywallNotFoundException()

                if (variant !in confirmedAssignments.values) {
                    confirmableAssignment = ConfirmableAssignment(
                        experimentId = rule.experiment.id,
                        variant = variant
                    )
                }

                when (variant.type) {
                    Experiment.Variant.VariantType.HOLDOUT -> Outcome(
                        confirmableAssignment = confirmableAssignment,
                        triggerResult = TriggerResult.Holdout(
                            Experiment(
                                id = rule.experiment.id,
                                groupId = rule.experiment.groupId,
                                variant = variant
                            )
                        )
                    )
                    Experiment.Variant.VariantType.TREATMENT -> Outcome(
                        confirmableAssignment = confirmableAssignment,
                        triggerResult = TriggerResult.Paywall(
                            Experiment(
                                id = rule.experiment.id,
                                groupId = rule.experiment.groupId,
                                variant = variant
                            )
                        )
                    )
                }
            }
        }
    }

    private suspend fun findMatchingRule(
        event: EventData,
        trigger: Trigger,
        isPreemptive: Boolean
    ): TriggerRule? {
        val expressionEvaluator = ExpressionEvaluator(context, storage, factory)
        return trigger.rules.firstOrNull {
            expressionEvaluator.evaluateExpression(
                it,
                event,
                isPreemptive
            )
        }
    }
}

class PaywallNotFoundException :
    Exception("There isn't a paywall configured to show in this context")
