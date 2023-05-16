package com.superwall.sdk.models.serialization

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.net.URL

object URLSerializer : KSerializer<URL> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("URL", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: URL) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): URL {
        val urlString = decoder.decodeString()
        return URL(urlString)
    }
}
