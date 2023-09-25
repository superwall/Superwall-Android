package com.superwall.sdk.models.serialization

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

object AnySerializer : KSerializer<Any> {

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Any", StructureKind.OBJECT)

    @OptIn(InternalSerializationApi::class)
    private val listDescriptor: SerialDescriptor = buildSerialDescriptor("List<Any>", StructureKind.LIST)

    @OptIn(InternalSerializationApi::class)
    private val mapDescriptor: SerialDescriptor = buildSerialDescriptor("Map<String, Any>", StructureKind.MAP)

    override fun serialize(encoder: Encoder, value: Any) {
        when (value) {
            is String -> encoder.encodeString(value)
            is Boolean -> encoder.encodeBoolean(value)
            is Int -> encoder.encodeInt(value)
            is Long -> encoder.encodeLong(value)
            is Float -> encoder.encodeFloat(value)
            is Double -> encoder.encodeDouble(value)
            is List<*> -> {
                val composite = encoder.beginCollection(listDescriptor, value.size)
                value.forEachIndexed { index, item ->
                    if (item != null) {
                        val serializer = serializerFor(item)
                        composite.encodeSerializableElement(listDescriptor, index, serializer, item)
                    }
                }
                composite.endStructure(listDescriptor)
            }

            is Map<*, *> -> {
                val composite = encoder.beginStructure(mapDescriptor)
                value.entries.forEachIndexed { index, entry ->
                    if (entry.value != null) {
                        val serializer = serializerFor(entry.value!!)
                        composite.encodeSerializableElement(mapDescriptor, index, serializer, entry.value!!)
                    }
                }
                composite.endStructure(mapDescriptor)
            }
            null -> encoder.encodeNull()
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

            element is JsonObject -> {
                val map = mutableMapOf<String, Any>()
                element.forEach { (key, value) ->
                    map[key] = when {
                        value is JsonPrimitive && value.isString -> value.content
                        value is JsonPrimitive && value.booleanOrNull != null -> value.boolean
                        value is JsonPrimitive && value.intOrNull != null -> value.int
                        value is JsonPrimitive && value.longOrNull != null -> value.long
                        value is JsonPrimitive && value.doubleOrNull != null -> value.double
                        value is JsonObject -> deserialize(Json.decodeFromString(value.toString()))
                        value is JsonArray -> value.map { deserialize(Json.decodeFromString(it.toString())) }
                        else -> throw SerializationException("Unknown type in JsonObject")
                    }
                }
                map
            }

            element is JsonArray -> {
                element.map { deserialize(Json.decodeFromString(it.toString())) }
            }

            else -> throw SerializationException("Unknown type")
        }
    }

    // Helper function to get the appropriate serializer for a value
    fun serializerFor(value: Any): KSerializer<Any> {
        return when (value) {
            is String -> String.serializer()
            is Boolean -> Boolean.serializer()
            is Int -> Int.serializer()
            is Long -> Long.serializer()
            is Float -> Float.serializer()
            is Double -> Double.serializer()
            is List<*> -> ListSerializer(AnySerializer)
            is Map<*, *> -> MapSerializer(String.serializer(), AnySerializer)
            else -> AnySerializer
        } as KSerializer<Any>
    }
}