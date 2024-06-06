package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import org.json.JSONObject
import org.junit.Test
import java.util.*

class ExpressionEvaluatorParamsTest {
    @Test
    fun expression_evaluator_params_test() {
        val expected = """
    {
        "expression": "user.id == '123'",
        "values": {
            "user": {
                "id": "123",
                "email": "test@gmail.com"
            },
            "device": {},
            "params": {
                "id": "567"
            }
        }
    }
    """

        val jsonValues = JSONObject()
        jsonValues.put("user", JSONObject(mapOf("id" to "123", "email" to "test@gmail.com")))
        jsonValues.put("device", JSONObject(emptyMap<String, String>()))
        jsonValues.put("params", JSONObject(mapOf("id" to "567")))

        val liquidExpressionParams =
            LiquidExpressionEvaluatorParams(
                expression = "user.id == '123'",
                values = jsonValues,
            )

        val jsonString = liquidExpressionParams.toJson()
        println("!! jsonString: $jsonString")

        // Parse jsonString into a JSONObject
        val parsedJson = JSONObject(jsonString)

        // Test top-level properties
        assert(parsedJson.getString("expression") == "user.id == '123'")

        // Test nested properties
        val values = parsedJson.getJSONObject("values")

        val user = values.getJSONObject("user")
        assert(user.getString("id") == "123")
        assert(user.getString("email") == "test@gmail.com")

        val device = values.getJSONObject("device")
        assert(device.names() == null) // Check that device is empty

        val params = values.getJSONObject("params")
        assert(params.getString("id") == "567")

        val base64String = liquidExpressionParams.toBase64Input()
        // Try to base64 decode the string
        val decodedString = Base64.getDecoder().decode(base64String)
        //  Parse the json
        val parsedJson2 = JSONObject(String(decodedString, Charsets.UTF_8))

        // Test top-level properties
        assert(parsedJson2.getString("expression") == "user.id == '123'")

        // Test nested properties
        val values2 = parsedJson2.getJSONObject("values")

        val user2 = values2.getJSONObject("user")
        assert(user2.getString("id") == "123")
        assert(user2.getString("email") == "test@gmail.com")

        val device2 = values2.getJSONObject("device")
        assert(device2.names() == null) // Check that device2 is empty

        val params2 = values2.getJSONObject("params")
        assert(params2.getString("id") == "567")
    }

    @Test
    fun javascript_expression_evaluator_params_test() {
        val expected = """
    {
        "expressionJS": "user.id == '123'",
        "values": {
            "user": {
                "id": "123",
                "email": "test@gmail.com"
            },
            "device": {},
            "params": {
                "id": "567"
            }
        }
    }
    """

        val jsonValues = JSONObject()
        jsonValues.put("user", JSONObject(mapOf("id" to "123", "email" to "test@gmail.com")))
        jsonValues.put("device", JSONObject(emptyMap<String, String>()))
        jsonValues.put("params", JSONObject(mapOf("id" to "567")))

        val jsExpressionParams =
            JavascriptExpressionEvaluatorParams(
                expressionJs = "user.id == '123'",
                values = jsonValues,
            )

        val jsonString = jsExpressionParams.toJson()

        // Parse jsonString into a JSONObject
        val parsedJson = JSONObject(jsonString)

        // Test top-level properties
        assert(parsedJson.getString("expressionJS") == "user.id == '123'")

        // Test nested properties
        val values = parsedJson.getJSONObject("values")

        val user = values.getJSONObject("user")
        assert(user.getString("id") == "123")
        assert(user.getString("email") == "test@gmail.com")

        val device = values.getJSONObject("device")
        assert(device.names() == null) // Check that device is empty

        val params = values.getJSONObject("params")
        assert(params.getString("id") == "567")

        val base64String = jsExpressionParams.toBase64Input()
        // Try to base64 decode the string
        val decodedByteArray = Base64.getDecoder().decode(base64String)
        val decodedString = String(decodedByteArray, Charsets.UTF_8)
        // Parse the json
        val parsedJson2 = JSONObject(decodedString)

        // Test top-level properties
        assert(parsedJson2.getString("expressionJS") == "user.id == '123'")

        // Test nested properties
        val values2 = parsedJson2.getJSONObject("values")

        val user2 = values2.getJSONObject("user")
        assert(user2.getString("id") == "123")
        assert(user2.getString("email") == "test@gmail.com")

        val device2 = values2.getJSONObject("device")
        assert(device2.names() == null) // Check that device2 is empty

        val params2 = values2.getJSONObject("params")
        assert(params2.getString("id") == "567")
    }
}
