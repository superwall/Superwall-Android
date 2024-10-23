package com.superwall.sdk.models.serialization

import com.superwall.sdk.models.paywall.PaywallURL
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object PaywallURLSerializer : KSerializer<PaywallURL> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PaywallURL", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: PaywallURL,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): PaywallURL {
        val urlString = decoder.decodeString()
        return PaywallURL(urlString)
    }
}
