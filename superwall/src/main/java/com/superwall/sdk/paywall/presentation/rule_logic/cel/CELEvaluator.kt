package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ExecutionContext
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableMap
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluating
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.supercel.HostContext
import com.superwall.supercel.evaluateWithContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

class CELEvaluator(
    json: Json,
    private val storage: CoreDataManager,
    private val factory: RuleAttributesFactory,
) : ExpressionEvaluating {
    private val json =
        Json(json) {
            classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS
            classDiscriminator = "type"
        }
    private val availableComputedProperties =
        mapOf(
            "daysSince" to ComputedPropertyRequest.ComputedPropertyRequestType.DAYS_SINCE,
            "minutesSince" to ComputedPropertyRequest.ComputedPropertyRequestType.MINUTES_SINCE,
            "hoursSince" to ComputedPropertyRequest.ComputedPropertyRequestType.HOURS_SINCE,
            "monthsSince" to ComputedPropertyRequest.ComputedPropertyRequestType.MONTHS_SINCE,
        )

    override suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?,
    ): TriggerRuleOutcome {
        val factory = factory.makeRuleAttributes(eventData, rule.computedPropertyRequests)
        val userAttributes = factory.toPassableValue()
        val expression =
            (
                rule.expressionCEL
                    ?: run {
                        rule.expression ?: "".replace("and", "&&").replace("or", "||")
                    }
            ).replace("device.", "computed.")

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

        val result =
            evaluateWithContext(
                json.encodeToString(executionContext),
                object : HostContext {
                    override suspend fun computedProperty(
                        name: String,
                        args: String,
                    ): String {
                        val _args = json.decodeFromString<List<PassableValue>>(args)
                        if (!availableComputedProperties.containsKey(name)) {
                            return json.encodeToString(PassableValue.BoolValue(false).toString())
                        }

                        val res =
                            storage.getComputedPropertySinceEvent(
                                null,
                                ComputedPropertyRequest(
                                    availableComputedProperties[name]!!,
                                    (_args.first() as PassableValue.StringValue).value,
                                ),
                            )
                        return json.encodeToString(PassableValue.IntValue(res ?: 0).toString())
                    }

                    // Temporary solution until CEL lib is updated
                    override suspend fun deviceProperty(
                        name: String,
                        args: String,
                    ): String {
                        val _args = json.decodeFromString<List<PassableValue>>(args)
                        if (!availableComputedProperties.containsKey(name)) {
                            return json.encodeToString(PassableValue.BoolValue(false).toString())
                        }

                        val res =
                            storage.getComputedPropertySinceEvent(
                                null,
                                ComputedPropertyRequest(
                                    availableComputedProperties[name]!!,
                                    (_args.first() as PassableValue.StringValue).value,
                                ),
                            )
                        val encoded = json.encodeToString(PassableValue.IntValue(res ?: 0))
                        return encoded
                    }
                },
            )
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
