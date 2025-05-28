package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.supercel.HostContext
import com.superwall.supercel.ResultCallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CELHostContext(
    private val availableComputedProperties: Map<String, ComputedPropertyRequest.ComputedPropertyRequestType>,
    private val availableDeviceProperties: Map<String, ComputedPropertyRequest.ComputedPropertyRequestType>,
    private val json: Json,
    private val storage: CoreDataManager,
) : HostContext {
    override fun computedProperty(
        name: String,
        args: String,
        callback: ResultCallback,
    ) {
        val _args = json.decodeFromString<List<PassableValue>>(args)
        if (!availableComputedProperties.containsKey(name)) {
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
                        availableComputedProperties[name]!!,
                        (_args.first() as PassableValue.StringValue).value,
                    ),
                )
            }
        callback.onResult(json.encodeToString(PassableValue.IntValue(res ?: 0).toString()))
    }

    // Temporary solution until CEL lib is updated
    override fun deviceProperty(
        name: String,
        args: String,
        callback: ResultCallback,
    ) {
        val _args = json.decodeFromString<List<PassableValue>>(args)
        if (!availableDeviceProperties.containsKey(name)) {
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
                        availableDeviceProperties[name]!!,
                        (_args.first() as PassableValue.StringValue).value,
                    ),
                )
            }
        val encoded = json.encodeToString(PassableValue.IntValue(res ?: 0))
        callback.onResult(encoded)
    }
}
