package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator


import com.superwall.sdk.models.serialization.from
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.util.*

data class LiquidExpressionEvaluatorParams(
    val expression: String,
    val values: JsonObject
) {

    fun toJson(): String {
        return JsonObject.from(mapOf(
            "expressionJS" to expression,
            "values" to values
        )).toString()
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
    val values: JsonObject
) {

    fun toJson(): String {
        return JsonObject.from(mapOf(
            "expressionJS" to expressionJs,
            "values" to values
        )).toString()
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