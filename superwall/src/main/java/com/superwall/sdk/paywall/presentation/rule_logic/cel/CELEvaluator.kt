package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.celExpression
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluating
import com.superwall.sdk.paywall.presentation.rule_logic.toPassableValue
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.Storage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.cel.evaluateWithContext

class CELEvaluator(
    private val json: Json,
    private val storage: Storage,
    private val factory: RuleAttributesFactory,
) : ExpressionEvaluating {
    override suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?,
    ): TriggerRuleOutcome {
        val rewrittenExpression = rule.celExpression()
        TriggerRuleOutcome.noMatch(
            UnmatchedRule.Source.EXPRESSION,
            rule.experiment.id,
        )
        val factory = factory.makeRuleAttributes(eventData, rule.computedPropertyRequests)
        val toPassableValue = factory.toPassableValue()
        val executionContext =
            ExecutionContext(
                variables = PassableMap(map = toPassableValue.value),
                expression = rewrittenExpression,
            )
        val result = evaluateWithContext(json.encodeToString(executionContext))
        return if (result == "true") {
            rule.tryToMatchOccurrence(storage, true)
        } else {
            TriggerRuleOutcome.noMatch(
                UnmatchedRule.Source.EXPRESSION,
                rule.experiment.id,
            )
        }
    }
}

private fun TriggerRule.celExpression(): String = expression?.replace("and", "&&")?.replace("or", "||") ?: "true"

//
private fun Map<String, Any>.toPassableValue(): PassableValue.MapValue {
    val passableMap =
        this.mapValues { (_, value) ->
            value.toPassableValue()
        }
    return PassableValue.MapValue(passableMap)
}

private fun Any.toPassableValue(): PassableValue =
    when (this) {
        is Int -> PassableValue.IntValue(this)
        is Long -> PassableValue.UIntValue(this)
        is Double -> PassableValue.FloatValue(this)
        is String -> PassableValue.StringValue(this)
        is ByteArray -> PassableValue.BytesValue(this)
        is Boolean -> PassableValue.BoolValue(this)
        is List<*> -> PassableValue.ListValue(this.map { it?.toPassableValue() ?: PassableValue.NullValue })
        is Map<*, *> -> {
            val stringKeyMap = this.filterKeys { it is String }.mapKeys { it.key as String }
            PassableValue.MapValue(stringKeyMap.mapValues { it.value?.toPassableValue() ?: PassableValue.NullValue })
        }
        else -> PassableValue.NullValue
    }