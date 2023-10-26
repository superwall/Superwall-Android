package com.superwall.sdk.models.events

import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.models.serialization.DateSerializer
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import java.util.*

@Serializable
internal data class EventData(
    @SerialName("event_id")
    val id: String = UUID.randomUUID().toString(),
    @SerialName("event_name")
    val name: String,
    val parameters: Map<String, @Serializable(with = AnySerializer::class) Any>,
    @Serializable(with = DateSerializer::class)
    val createdAt: Date,
) {

    companion object {
        fun stub(): EventData {
            return EventData(
                name = "opened_application",
                parameters = emptyMap(),
                createdAt = Date()
            )
        }
    }
}

