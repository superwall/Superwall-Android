package com.superwall.sdk.misc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    for (key in this.keys()) {
        val value = this.opt(key)  // opt() gets the value or null if the key does not exist
        when {
            value == JSONObject.NULL -> map[key] = null
            value is JSONObject -> map[key] = value.toMap()
            value is JSONArray -> map[key] = value.toList()
            else -> map[key] = value
        }
    }
    return map
}

fun JSONArray.toList(): List<Any?> {
    return (0 until this.length()).map { idx ->
        val value = this.opt(idx)  // opt() gets the value or null if the index is out-of-bounds
        when {
            value == JSONObject.NULL -> null
            value is JSONObject -> value.toMap()
            value is JSONArray -> value.toList()
            else -> value
        }
    }
}


object JSONObjectSerializer : KSerializer<JSONObject> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JSONObject", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JSONObject) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): JSONObject {
        return JSONObject(decoder.decodeString())
    }
}