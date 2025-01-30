package com.superwall.sdk.models.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import java.util.Calendar

/**
 * A request to compute a device property associated with an event at runtime.
 */
@Serializable
data class ComputedPropertyRequest(
    val type: ComputedPropertyRequestType,
    val placementName: String,
) {
    /**
     * The type of device property to compute.
     */
    @Serializable(with = ComputedPropertyRequestTypeSerializer::class)
    enum class ComputedPropertyRequestType(
        val rawValue: String,
    ) {
        @SerialName("MINUTES_SINCE")
        MINUTES_SINCE("MINUTES_SINCE"),

        @SerialName("HOURS_SINCE")
        HOURS_SINCE("HOURS_SINCE"),

        @SerialName("DAYS_SINCE")
        DAYS_SINCE("DAYS_SINCE"),

        @SerialName("MONTHS_SINCE")
        MONTHS_SINCE("MONTHS_SINCE"),

        @SerialName("YEARS_SINCE")
        YEARS_SINCE("YEARS_SINCE"),
        ;

        val prefix: String
            get() =
                when (this) {
                    MINUTES_SINCE -> "minutesSince_"
                    HOURS_SINCE -> "hoursSince_"
                    DAYS_SINCE -> "daysSince_"
                    MONTHS_SINCE -> "monthsSince_"
                    YEARS_SINCE -> "yearsSince_"
                }

        val calendarComponent: Int
            get() =
                when (this) {
                    MINUTES_SINCE -> Calendar.MINUTE
                    HOURS_SINCE -> Calendar.HOUR_OF_DAY
                    DAYS_SINCE -> Calendar.DAY_OF_MONTH
                    MONTHS_SINCE -> Calendar.MONTH
                    YEARS_SINCE -> Calendar.YEAR
                }

        fun dateComponent(components: Map<Int, Int>): Int? =
            when (this) {
                MINUTES_SINCE -> components[Calendar.MINUTE]
                HOURS_SINCE -> components[Calendar.HOUR_OF_DAY]
                DAYS_SINCE -> components[Calendar.DAY_OF_MONTH]
                MONTHS_SINCE -> components[Calendar.MONTH]
                YEARS_SINCE -> components[Calendar.YEAR]
            }
    }

    @Serializer(forClass = ComputedPropertyRequestType::class)
    object ComputedPropertyRequestTypeSerializer : KSerializer<ComputedPropertyRequestType> {
        override fun serialize(
            encoder: kotlinx.serialization.encoding.Encoder,
            value: ComputedPropertyRequestType,
        ) {
            encoder.encodeString(value.rawValue)
        }

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ComputedPropertyRequestType {
            val rawValue = decoder.decodeString()
            return ComputedPropertyRequestType.values().find { it.rawValue == rawValue }
                ?: throw IllegalArgumentException("Unsupported computed property type.")
        }
    }
}
