package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator


import kotlinx.serialization.SerializationException
import org.json.JSONObject
import java.util.*

data class LiquidExpressionEvaluatorParams(
    val expression: String,
    val values: JSONObject
) {

    fun toJson(): String {
        var obj = JSONObject()
        obj.put("expression", expression)
        obj.put("values", values)
        return obj.toString()
    }

    fun toBase64Input(): String? {
        return try {
            val jsonString = toJson()
            println("!! jsonString: $jsonString")
            jsonString.encodeToByteArray().toBase64()
        } catch (e: SerializationException) {
            null
        }
    }
}

data class JavascriptExpressionEvaluatorParams(
    val expressionJs: String,
    val values: JSONObject
) {

    fun toJson(): String {
        var obj = JSONObject()
        obj.put("expressionJS", expressionJs)
        obj.put("values", values)
        return obj.toString()
    }

    fun toBase64Input(): String? {
        return try {
            toJson().encodeToByteArray().toBase64()
        } catch (e: SerializationException) {
            null
        }
    }
}

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)