package com.superwall.sdk.misc

import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

//fun JSONObject.toMap(): Map<String, Any?> {
//    val map = mutableMapOf<String, Any?>()
//    for (key in this.keys()) {
//        val value = this.opt(key)  // opt() gets the value or null if the key does not exist
//        when {
//            value == JSONObject.NULL -> map[key] = null
//            value is JSONObject -> map[key] = value.toMap()
//            value is JSONArray -> map[key] = value.toList()
//            else -> map[key] = value
//        }
//    }
//    return map
//}
//
//fun JSONArray.toList(): List<Any?> {
//    return (0 until this.length()).map { idx ->
//        val value = this.opt(idx)  // opt() gets the value or null if the index is out-of-bounds
//        when {
//            value == JSONObject.NULL -> null
//            value is JSONObject -> value.toMap()
//            value is JSONArray -> value.toList()
//            else -> value
//        }
//    }
//}

//object JSONObjectSerializer : KSerializer<JSONObject> {
//    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JSONObject", PrimitiveKind.STRING)
//
//    override fun serialize(encoder: Encoder, value: JSONObject) {
//        encoder.encodeString(value.toString())
//    }
//
//    override fun deserialize(decoder: Decoder): JSONObject {
//        return JSONObject(decoder.decodeString())
//    }
//}
//
//object JSONObjectSerializer : KSerializer<JSONObject> {
//    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JSONObject") {
//        element<String>("any_element_name", isOptional = true) // This is a placeholder; we won't use it directly.
//    }
//
//    override fun serialize(encoder: Encoder, value: JSONObject) {
//        val jsonContent = value.toString()
//        val parser = Json { isLenient = true; ignoreUnknownKeys = true }
//        val map = parser.decodeFromString<Map<String, Any>>(jsonContent)
//        encoder.encodeSerializableValue(MapSerializer(String.serializer(), AnySerializer), map)
//    }
//
//    override fun deserialize(decoder: Decoder): JSONObject {
//        val map = decoder.decodeSerializableValue(MapSerializer(String.serializer(), AnySerializer))
//        return JSONObject(map)
//    }
//}
