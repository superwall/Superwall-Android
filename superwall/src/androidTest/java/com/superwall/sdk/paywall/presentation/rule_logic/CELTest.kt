package com.superwall.sdk.paywall.presentation.rule_logic

import android.util.Log
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.CELResult
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ExecutionContext
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.supercel.HostContext
import com.superwall.supercel.evaluateWithContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class CELTest {
    val json =
        Json {
        }

    @Test
    fun deserialize_cel_from_json() {
        val celState = """        {
            "variables": {
                "map": {
                    "numbers": {
                        "type":"list",
                        "value": [
                            {"type": "int", "value": 100},
                            {"type": "int", "value": 100},
                            {"type": "int", "value": 100}
                        ]
                    }
                }
            },
            "computed": {},
            "device": {},
            "expression": "numbers[0] + numbers[1] == 200"
        }
"""

        val executionContext = json.decodeFromString<ExecutionContext>(celState)
        println("Deserialized: $executionContext")

        val serializedJson = json.encodeToString(executionContext)
        println("Serialized JSON: $serializedJson")
    }

    @Test
    fun deserialize_cel_function_from_json() {
        val celState = """        {
            "variables": {
                "map": {
                    "numbers": {
                        "type":"list",
                        "value": [
                            {"type": "int", "value": 100},
                            {"type": "int", "value": 100},
                            {"type": "int", "value": 100}
                        ]
                    }
                }
            },
            "expression": "numbers[0] + numbers[1] == 200"
        }
"""

        val executionContext = json.decodeFromString<ExecutionContext>(celState)
        println("Deserialized: $executionContext")

        val serializedJson = json.encodeToString(executionContext)
        println("Serialized JSON: $serializedJson")
        val resultJSON =
            evaluateWithContext(
                celState,
                object : HostContext {
                    override suspend fun computedProperty(
                        name: String,
                        args: String,
                    ): String =
                        Json.encodeToString(
                            PassableValue.UIntValue(0uL),
                        )

                    override suspend fun deviceProperty(
                        name: String,
                        args: String,
                    ): String =
                        Json.encodeToString(
                            PassableValue.UIntValue(0uL),
                        )
                },
            )
        val result = json.decodeFromString<CELResult>(resultJSON)
        assert(result is CELResult.Ok && result.value == PassableValue.BoolValue(true))
    }

    @Test
    fun evaluate_expression_from_rules() {
        val celState = """        {
                    "variables": {
                        "map": {
                            "user": {
                                "type": "map",
                                "value": {
                                    "should_display": {
                                        "type": "bool",
                                        "value": true
                                    },
                                    "some_value": {
                                        "type": "uint",
                                        "value": 13
                                    }
                                }
                            }
                        }
                    },
                    "platform" : {
                        "daysSinceEvent": "9"
                    },
                    "expression": "platform.daysSinceEvent"
    }
"""
        val executionContext = json.decodeFromString<ExecutionContext>(celState)
        val serializedJson = json.encodeToString(executionContext)
        val resultJSON =
            evaluateWithContext(
                celState,
                object : HostContext {
                    override suspend fun computedProperty(
                        name: String,
                        args: String,
                    ): String {
                        val res = json.encodeToString(PassableValue.UIntValue(3uL) as PassableValue)
                        Log.e("CELTest", "computedProperty: $name -> $res")
                        return res
                    }

                    override suspend fun deviceProperty(
                        name: String,
                        args: String,
                    ): String {
                        val res = json.encodeToString(PassableValue.UIntValue(3uL) as PassableValue)
                        Log.e("CELTest", "computedProperty: $name -> $res")
                        return res
                    }
                },
            )
        val result = json.decodeFromString<CELResult>(resultJSON)
        assert(result is CELResult.Ok && result.value == PassableValue.BoolValue(true))
    }
}
