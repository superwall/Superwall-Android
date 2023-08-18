package com.superwall.sdk.models.events

import com.superwall.sdk.models.serialization.AnyMapSerializer
import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.models.serialization.DateSerializer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import java.util.*


//object EventDataSerializer: KSerializer<EventData>  {
//    @OptIn(InternalSerializationApi::class)
//    override val descriptor: SerialDescriptor = buildSerialDescriptor("EventData", StructureKind.OBJECT)
//
//    override fun deserialize(decoder: Decoder): EventData {
//        TODO("Not yet implemented")
//    }
//
//    override fun serialize(encoder: Encoder, value: EventData) {
//        TODO("Not yet implemented")
//    }
//
//}

object EventDataSerializer : KSerializer<EventData> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("EventData") {
            element<String>("event_id")
            element<String>("event_name")
            element<Map<String, JsonElement>>("parameters")
            element<JsonElement>("createdAt")
        }

    override fun serialize(encoder: Encoder, value: EventData) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeStringElement(descriptor, 0, value.id)
        compositeEncoder.encodeStringElement(descriptor, 1, value.name)
        compositeEncoder.encodeSerializableElement(
            descriptor,
            2,
            AnyMapSerializer,
            value.parameters
        )
        compositeEncoder.encodeSerializableElement(descriptor, 3, DateSerializer, value.createdAt)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): EventData {
        lateinit var id: String
        lateinit var name: String
        lateinit var parameters: Map<String, Any>
        lateinit var createdAt: Date

        val dec: CompositeDecoder = decoder.beginStructure(descriptor)
        loop@ while (true) {
            when (val i = dec.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> id = dec.decodeStringElement(descriptor, i)
                1 -> name = dec.decodeStringElement(descriptor, i)
                2 -> parameters = dec.decodeSerializableElement(
                    descriptor,
                    i,
                    MapSerializer(String.serializer(), AnySerializer)
                )
                3 -> createdAt = dec.decodeSerializableElement(descriptor, i, DateSerializer)
                else -> throw SerializationException("Unknown index $i")
            }
        }
        dec.endStructure(descriptor)
        return EventData(id, name, parameters, createdAt)
    }
}


@kotlinx.serialization.Serializable(with = EventDataSerializer::class)
data class EventData(
    @SerialName("event_id")
    var id: String = UUID.randomUUID().toString(),
    @SerialName("event_name")
    var name: String,
    var parameters: Map<String, @Serializable(with = AnySerializer::class) Any?>,
    @Serializable(with = DateSerializer::class)
    var createdAt: Date,
) {

    companion object {
        fun stub(): EventData {
            return EventData(
                name = "opened_application",
                parameters = mapOf<String, Any>(),
                createdAt = Date()
            )
        }
    }
}
