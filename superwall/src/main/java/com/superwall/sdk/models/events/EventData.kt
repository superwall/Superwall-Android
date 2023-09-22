package com.superwall.sdk.models.events

import com.superwall.sdk.models.serialization.AnyMapSerializer
import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.models.serialization.DateSerializer
import com.superwall.sdk.models.serialization.empty
import kotlinx.serialization.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.util.*

@kotlinx.serialization.Serializable
data class EventData(
    @SerialName("event_id")
    val id: String = UUID.randomUUID().toString(),
    @SerialName("event_name")
    val name: String,
    val parameters: JsonObject,
    @Serializable(with = DateSerializer::class)
    val createdAt: Date,
) {

    companion object {
        fun stub(): EventData {
            return EventData(
                name = "opened_application",
                parameters = JsonObject.empty(),
                createdAt = Date()
            )
        }
    }
}

