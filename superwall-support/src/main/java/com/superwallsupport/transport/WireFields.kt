package com.superwallsupport.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Why a value didn't decode, with the path to it. */
class WireDecodeIssue(
    val path: String,
    message: String,
) : Exception(if (path.isEmpty()) message else "$path: $message")

/** Wire id rules, the same patterns as `@superwall/inbox-contracts/ids`. */
internal object WireIds {
    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    private val prefixed = mutableMapOf<String, Regex>()
    private val clientMessageId = Regex("^[A-Za-z0-9_-]{1,36}$")

    @Synchronized
    fun matches(
        value: String,
        prefix: String,
    ): Boolean = prefixed.getOrPut(prefix) { Regex("^${prefix}_$ULID$") }.matches(value)

    fun isClientMessageId(value: String): Boolean = clientMessageId.matches(value)
}

internal val wireJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/** Parses JSON text; null for anything that isn't JSON. */
internal fun parseJson(text: String): JsonElement? =
    try {
        wireJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

internal val JsonElement.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonElement.boolOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal val JsonElement.numberOrNull: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull

/** An integer, only when the number is integral and exactly representable. */
internal val JsonElement.intOrNull: Long?
    get() {
        val number = numberOrNull ?: return null
        if (number != Math.floor(number) || Math.abs(number) > 9_007_199_254_740_991.0) return null
        return number.toLong()
    }

/**
 * Reads one JSON object strictly, the way the TypeScript schemas do: a required
 * key must be present with the right type, an optional key may be absent but not
 * present with the wrong type (null included). Unknown keys are ignored.
 */
private val ISO_8601 =
    // As broad as JavaScript's Date parsing for ISO forms (Schema.DateTimeUtcFromString).
    Regex("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?(Z|[+-]\\d{2}:?\\d{2})?$")

internal class WireFields(
    value: JsonElement,
    private val path: String = "",
) {
    val obj: JsonObject = value as? JsonObject ?: throw WireDecodeIssue(path, "Expected an object")

    private fun at(key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun fail(
        key: String,
        message: String,
    ) = WireDecodeIssue(at(key), message)

    fun has(key: String) = obj.containsKey(key)

    fun value(key: String): JsonElement = obj[key] ?: throw fail(key, "Missing key")

    fun string(key: String): String = value(key).stringOrNull ?: throw fail(key, "Expected a string")

    fun optionalString(key: String): String? = if (has(key)) string(key) else null

    fun bool(key: String): Boolean = value(key).boolOrNull ?: throw fail(key, "Expected a boolean")

    fun optionalBool(key: String): Boolean? = if (has(key)) bool(key) else null

    fun long(key: String): Long = value(key).intOrNull ?: throw fail(key, "Expected an integer")

    fun int(key: String): Int = long(key).toInt()

    fun optionalInt(key: String): Int? = if (has(key)) int(key) else null

    fun number(key: String): Double = value(key).numberOrNull ?: throw fail(key, "Expected a number")

    /** A `Seq`: an integer at least 0. */
    fun seq(key: String): Long {
        val value = long(key)
        if (value < 0) throw fail(key, "Expected a seq at least 0")
        return value
    }

    fun id(
        key: String,
        prefix: String,
    ): String {
        val value = string(key)
        if (!WireIds.matches(value, prefix)) throw fail(key, "Expected a ${prefix}_ id")
        return value
    }

    fun optionalId(
        key: String,
        prefix: String,
    ): String? = if (has(key)) id(key, prefix) else null

    fun literal(
        key: String,
        allowed: Set<String>,
    ): String {
        val value = string(key)
        if (value !in allowed) throw fail(key, "Expected one of ${allowed.sorted().joinToString()}")
        return value
    }

    fun optionalLiteral(
        key: String,
        allowed: Set<String>,
    ): String? = if (has(key)) literal(key, allowed) else null

    fun timestamp(key: String): String {
        val value = string(key)
        // java.time needs API 26 and minSdk is 23, so the shape is checked here.
        if (!ISO_8601.matches(value)) throw fail(key, "Expected an ISO 8601 time")
        return value
    }

    fun optionalTimestamp(key: String): String? = if (has(key)) timestamp(key) else null

    fun array(key: String): JsonArray = value(key) as? JsonArray ?: throw fail(key, "Expected an array")

    fun optionalArray(key: String): JsonArray? = if (has(key)) array(key) else null

    fun objectFields(key: String): WireFields = WireFields(value(key), at(key))

    fun optionalObject(key: String): JsonObject? =
        if (has(key)) value(key) as? JsonObject ?: throw fail(key, "Expected an object") else null

    fun <T> each(
        key: String,
        decode: (JsonElement, String) -> T,
    ): List<T> = array(key).mapIndexed { index, element -> decode(element, "${at(key)}[$index]") }

    fun <T> optionalEach(
        key: String,
        decode: (JsonElement, String) -> T,
    ): List<T>? = if (has(key)) each(key, decode) else null
}
