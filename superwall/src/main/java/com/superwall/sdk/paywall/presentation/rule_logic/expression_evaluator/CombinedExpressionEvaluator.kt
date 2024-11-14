package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.cel.CELEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.JavascriptEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.LocalStorage
import org.json.JSONObject

interface ExpressionEvaluating {
    suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?,
    ): TriggerRuleOutcome
}

internal class CombinedExpressionEvaluator(
    private val storage: LocalStorage,
    private val factory: RuleAttributesFactory,
    private val evaluator: JavascriptEvaluator,
    private val celEvaluator: CELEvaluator,
    private val track: suspend (InternalSuperwallEvent.ExpressionResult) -> Unit,
) : ExpressionEvaluating {
    override suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?,
    ): TriggerRuleOutcome {
        // Expression matches all
        if (rule.expressionJs == null && rule.expression == null) {
            return rule.tryToMatchOccurrence(storage.coreDataManager, true)
        }

        val base64Params =
            getBase64Params(rule, eventData) ?: return TriggerRuleOutcome.noMatch(
                UnmatchedRule.Source.EXPRESSION,
                rule.experiment.id,
            )

        val result = evaluator.evaluate(base64Params, rule)
        val celEvaluation =
            try {
                celEvaluator.evaluateExpression(rule, eventData)
            } catch (e: Exception) {
                TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)
            }
        track(
            InternalSuperwallEvent.ExpressionResult(
                liquidExpression = rule.expression,
                celExpression = rule.expressionCEL,
                celExpressionResult = if (celEvaluation is TriggerRuleOutcome.Match) true else false,
                jsExpression = rule.expressionJs,
                jsExpressionResult = if (result is TriggerRuleOutcome.Match) true else false,
            ),
        )
        return result
    }

    private suspend fun getBase64Params(
        rule: TriggerRule,
        eventData: EventData?,
    ): String? {
        val jsonAttributes =
            factory.makeRuleAttributes(eventData, rule.computedPropertyRequests)

        rule.expressionJs?.let { expressionJs ->
            JavascriptExpressionEvaluatorParams(
                expressionJs,
                JSONObject(jsonAttributes),
            ).toBase64Input()?.let { base64Params ->
                return "\n SuperwallSDKJS.evaluateJS64('$base64Params');"
            }
        }

        rule.expression?.let { expression ->
            LiquidExpressionEvaluatorParams(
                expression,
                JSONObject(jsonAttributes),
            ).toBase64Input()?.let { base64Params ->
                return "\n SuperwallSDKJS.evaluate64('$base64Params');"
            }
        }

        return null
    }
}
