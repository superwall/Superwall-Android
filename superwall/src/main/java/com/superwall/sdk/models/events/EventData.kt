package com.superwall.sdk.models.events

import org.json.JSONObject
import java.util.*

data class EventData(
    var id: String = UUID.randomUUID().toString(),
    var name: String,
    var parameters: JSONObject,
    var createdAt: Date
) {
    fun toJson(): JSONObject {

            val json = JSONObject()
            json.put("event_id", id)
            json.put("event_name", name)
            json.put("parameters", parameters)
            json.put("created_at", createdAt.time) // Assuming ISO string conversion
            return json
        }

    companion object {
        fun stub(): EventData {
            return EventData(
                name = "opened_application",
                parameters = JSONObject(),
                createdAt = Date()
            )
        }
    }
}
