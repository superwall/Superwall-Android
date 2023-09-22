package com.superwall.sdk.models.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun JsonObject.Companion.empty(): JsonObject {
    return JsonObject(mapOf())
}

fun JsonObject.Companion.from(map: Map<String, Any>): JsonObject {
    return mapToJsonObject(map)
}

fun mapToJsonObject(map: Map<String, Any>): JsonObject {
    return JsonObject(map.mapValues { (_, value) ->
        convertValueToJsonElement(value)
    })
}

fun convertValueToJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> mapToJsonObject(value as Map<String, Any>)
        is List<*> -> JsonArray(value.map { convertValueToJsonElement(it) })
        is Any -> JsonPrimitive(value.toString())
        else -> error("Unsupported type")
    }
}