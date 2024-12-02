package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import android.util.Base64
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import kotlinx.serialization.SerializationException
import org.json.JSONObject

data class LiquidExpressionEvaluatorParams(
    val expression: String,
    val values: JSONObject,
) {
    fun toJson(): String {
        var obj = JSONObject()
        obj.put("expression", expression)
        obj.put("values", values)
        return obj.toString()
    }

    fun toBase64Input(): String? =
        try {
            val jsonString = toJson()
            Logger.debug(
                LogLevel.debug,
                LogScope.all,
                "!! jsonString: $jsonString",
            )
            jsonString.encodeToByteArray().toBase64()
        } catch (e: SerializationException) {
            null
        }
}

data class JavascriptExpressionEvaluatorParams(
    val expressionJs: String,
    val values: JSONObject,
) {
    fun toJson(): String {
        var obj = JSONObject()
        obj.put("expressionJS", expressionJs)
        obj.put("values", values)
        return obj.toString()
    }

    fun toBase64Input(): String? =
        try {
            toJson().encodeToByteArray().toBase64()
        } catch (e: SerializationException) {
            null
        }
}

fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
