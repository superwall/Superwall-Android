package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.triggers.RawInterval
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.supercel.HostContext
import com.superwall.supercel.ResultCallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CELHostContext(
    private val json: Json,
    private val storage: CoreDataManager,
) : HostContext {
    companion object ComputedProperties {
        private val periodSinceFunctionProperties =
            mapOf(
                "daysSince" to ComputedPropertyRequest.ComputedPropertyRequestType.DAYS_SINCE,
                "minutesSince" to ComputedPropertyRequest.ComputedPropertyRequestType.MINUTES_SINCE,
                "hoursSince" to ComputedPropertyRequest.ComputedPropertyRequestType.HOURS_SINCE,
                "monthsSince" to ComputedPropertyRequest.ComputedPropertyRequestType.MONTHS_SINCE,
            )

        val OCCURENCES_FOR_TRIGGER = "occurencesForTrigger"

        val availableComputedProperties =
            periodSinceFunctionProperties.keys.toList() + OCCURENCES_FOR_TRIGGER
        val availableDeviceProperties = periodSinceFunctionProperties.keys
    }

    override fun computedProperty(
        name: String,
        args: String,
        callback: ResultCallback,
    ) {
        val _args = json.decodeFromString<List<PassableValue>>(args)
        if (!availableComputedProperties.contains(name)) {
            callback.onResult(
                json.encodeToString(
                    PassableValue.BoolValue(false).toString(),
                ),
            )
        }

        val res =
            runBlocking {
                if (name == OCCURENCES_FOR_TRIGGER) {
                    storage.countTriggerRuleOccurrences(
                        TriggerRuleOccurrence(
                            (args.first() as PassableValue.StringValue).value,
                            Int.MAX_VALUE,
                            rawInterval =
                                RawInterval(
                                    RawInterval.IntervalType.INFINITY,
                                ),
                        ),
                    )
                } else {
                    storage.getComputedPropertySinceEvent(
                        null,
                        ComputedPropertyRequest(
                            periodSinceFunctionProperties[name]!!,
                            (_args.first() as PassableValue.StringValue).value,
                        ),
                    )
                }
            }
        callback.onResult(json.encodeToString(res?.toPassableValue() ?: PassableValue.NullValue))
    }

    // Temporary solution until CEL lib is updated
    override fun deviceProperty(
        name: String,
        args: String,
        callback: ResultCallback,
    ) {
        val _args = json.decodeFromString<List<PassableValue>>(args)
        if (!availableDeviceProperties.contains(name)) {
            callback.onResult(
                json.encodeToString(
                    PassableValue.BoolValue(false).toString(),
                ),
            )
        }

        val res =
            runBlocking {
                storage.getComputedPropertySinceEvent(
                    null,
                    ComputedPropertyRequest(
                        periodSinceFunctionProperties[name]!!,
                        (_args.first() as PassableValue.StringValue).value,
                    ),
                )
            }
        val encoded = json.encodeToString(PassableValue.IntValue(res ?: 0))
        callback.onResult(encoded)
    }
}
