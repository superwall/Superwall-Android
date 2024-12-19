package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.asyncWithTracking
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.CELResult
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ExecutionContext
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableMap
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluating
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.supercel.HostContext
import com.superwall.supercel.evaluateWithContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class SuperscriptEvaluator(
    private val json: Json,
    private val ioScope: IOScope,
    private val storage: CoreDataManager,
    private val factory: RuleAttributesFactory,
    private val hostContext: HostContext =
        CELHostContext(
            availableComputedProperties,
            emptyMap(),
            json,
            storage,
        ),
) : ExpressionEvaluating {
    private companion object {
        private val availableComputedProperties =
            mapOf(
                "daysSince" to ComputedPropertyRequest.ComputedPropertyRequestType.DAYS_SINCE,
                "minutesSince" to ComputedPropertyRequest.ComputedPropertyRequestType.MINUTES_SINCE,
                "hoursSince" to ComputedPropertyRequest.ComputedPropertyRequestType.HOURS_SINCE,
                "monthsSince" to ComputedPropertyRequest.ComputedPropertyRequestType.MONTHS_SINCE,
            )
    }

    override suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?,
    ): TriggerRuleOutcome {
        if (rule.expressionCEL == null) {
            return rule.tryToMatchOccurrence(storage, true)
        }

        return ioScope
            .asyncWithTracking {
                val factory = factory.makeRuleAttributes(eventData, rule.computedPropertyRequests)
                val userAttributes = factory.toPassableValue()
                val expression =
                    rule.expressionCEL

                val executionContext =
                    ExecutionContext(
                        variables = PassableMap(map = userAttributes.value),
                        expression = expression,
                        device =
                            availableComputedProperties.mapValues {
                                listOf(PassableValue.StringValue("event_name"))
                            },
                        computed =
                            availableComputedProperties.mapValues {
                                listOf(PassableValue.StringValue("event_name"))
                            },
                    )

                val ctx = json.encodeToString(executionContext)
                val result =
                    evaluateWithContext(ctx, hostContext)

                val celResult = json.decodeFromString<CELResult>(result)
                return@asyncWithTracking when (celResult) {
                    is CELResult.Err ->
                        TriggerRuleOutcome.noMatch(
                            UnmatchedRule.Source.EXPRESSION,
                            rule.experiment.id,
                        )

                    is CELResult.Ok -> {
                        if (celResult.value is PassableValue.BoolValue && celResult.value.value
                        ) {
                            rule.tryToMatchOccurrence(storage, true)
                        } else {
                            TriggerRuleOutcome.noMatch(
                                UnmatchedRule.Source.EXPRESSION,
                                rule.experiment.id,
                            )
                        }
                    }
                }
            }.await()
            .getSuccess() ?: TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)
    }
}

// PassableValues match the types in our Rust package
internal fun Map<String, Any>.toPassableValue(): PassableValue.MapValue {
    val passableMap =
        this.mapValues { (_, value) ->
            value.toPassableValue()
        }
    return PassableValue.MapValue(passableMap)
}

private fun Any.toPassableValue(): PassableValue =
    when (this) {
        is Int -> PassableValue.IntValue(this)
        is Long -> PassableValue.UIntValue(this.toULong())
        is ULong -> PassableValue.UIntValue(this)
        is Double -> PassableValue.FloatValue(this)
        is String -> PassableValue.StringValue(this.replace("$", ""))
        is ByteArray -> PassableValue.BytesValue(this)
        is Boolean -> PassableValue.BoolValue(this)
        is List<*> ->
            PassableValue.ListValue(
                this.map {
                    it?.toPassableValue() ?: PassableValue.NullValue
                },
            )

        is Map<*, *> -> {
            val stringKeyMap = this.filterKeys { it is String }.mapKeys { it.key as String }
            PassableValue.MapValue(
                stringKeyMap.mapValues {
                    val toReturn = it.value?.toPassableValue() ?: PassableValue.NullValue
                    toReturn
                },
            )
        }

        is PassableValue -> this
        else -> throw IllegalArgumentException("Unsupported type: $this")
    }
