package com.superwall.sdk

import com.superwall.sdk.paywall.presentation.rule_logic.cel.ExecutionContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import uniffi.cel.evaluateWithContext

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
        val result = uniffi.cel.evaluateWithContext(celState)
        assert(result == "true")
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
                    "expression": "user.should_display == true && user.some_value > 12"
    }
"""
        val executionContext = json.decodeFromString<ExecutionContext>(celState)
        val serializedJson = json.encodeToString(executionContext)
        val result = evaluateWithContext(celState)
        assert(result == "true")
    }
}