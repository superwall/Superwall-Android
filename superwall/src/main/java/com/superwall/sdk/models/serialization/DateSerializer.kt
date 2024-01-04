package com.superwall.sdk.models.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.SimpleDateFormat
import java.util.*

@kotlinx.serialization.ExperimentalSerializationApi
@Serializer(forClass = Date::class)
object DateSerializer : KSerializer<Date> {
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun serialize(encoder: Encoder, value: Date) {
        encoder.encodeString(dateFormat.get()!!.format(value))

    }

    override fun deserialize(decoder: Decoder): Date {
        return try {
            dateFormat.get()!!.parse(decoder.decodeString())
        } catch (e: Throwable) {
            throw IllegalArgumentException("Invalid date format", e)
        }!!
    }
}
