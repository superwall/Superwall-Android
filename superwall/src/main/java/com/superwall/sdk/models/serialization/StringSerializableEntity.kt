package com.superwall.sdk.models.serialization

import com.superwall.sdk.models.SerializableEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 Complies pure Strings to SerializableEntity interface
 for usage with current Endpoint abstraction.

 TODO: Remove this class and use built-in String serializer
 */
@Serializable(with = StringSerializableEntity.StringSerializer::class)
class StringSerializableEntity(
    val value: String
) : SerializableEntity {

    @kotlinx.serialization.ExperimentalSerializationApi
    @Serializer(forClass = StringSerializableEntity::class)
    object StringSerializer : KSerializer<StringSerializableEntity> {
        override fun serialize(encoder: Encoder, value: StringSerializableEntity) =
            encoder.encodeString(value.value)

        override fun deserialize(decoder: Decoder): StringSerializableEntity =
            StringSerializableEntity(decoder.decodeString())
    }
}