package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.cel.HostContext

class PlatformProxy(
    private val json: Json,
) : ASTEvaluator.PlatformOperations,
    HostContext {
    private val methodMap =
        mapOf<String, (List<PassableValue>) -> PassableValue>(
            "json" to { args -> PassableValue.StringValue(json.encodeToString(args)) },
        )

    override fun invoke(
        name: String,
        args: List<PassableValue>,
    ): PassableValue {
        // TODO: Implement it all
        return PassableValue.NullValue
    }

    override fun computedProperty(
        name: String,
        args: String,
    ): String = json.encodeToString(invoke(name, json.decodeFromString(args)))
}
