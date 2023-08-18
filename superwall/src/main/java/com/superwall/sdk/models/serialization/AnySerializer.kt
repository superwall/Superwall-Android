package com.superwall.sdk.models.serialization

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*


object AnySerializer : KSerializer<Any> {

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Any", StructureKind.OBJECT)

    override fun serialize(encoder: Encoder, value: Any) {
        when (value) {
            is String -> encoder.encodeString(value)
            is Boolean -> encoder.encodeBoolean(value)
            is Int -> encoder.encodeInt(value)
            is Long -> encoder.encodeLong(value)
            is Float -> encoder.encodeFloat(value)
            is Double -> encoder.encodeDouble(value)
            else -> {
                println("Warning: Unsupported type ${value::class}, skipping...")
                encoder.encodeNull()
            }
        }
    }

    override fun deserialize(decoder: Decoder): Any {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("This class can be loaded only by Json")
        val element = input.decodeJsonElement()
        return when {
            element is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.boolean
                    element.intOrNull != null -> element.int
                    element.longOrNull != null -> element.long
                    element.doubleOrNull != null -> element.double
                    else -> throw SerializationException("Unknown primitive type")
                }
            }
            else -> println("Warning: Unsupported type ${element::class}, skipping...")
        }
    }
}