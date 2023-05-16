package com.superwall.sdk.models.events

import org.json.JSONArray
import org.json.JSONObject

enum class Status(val status: String) {
    OK("ok"),
    PARTIAL_SUCCESS("partial_success");

    companion object {
        fun from(statusString: String): Status {
            return values().find { it.status == statusString } ?: PARTIAL_SUCCESS
        }
    }
}

data class EventsResponse(val json: JSONObject) {
    val status: Status = Status.from(json.optString("status", Status.PARTIAL_SUCCESS.status))
    val invalidIndexes: List<Int> = json.optJSONArray("invalid_indexes")?.let { jsonArray ->
        val list = mutableListOf<Int>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getInt(i))
        }
        list
    } ?: emptyList()
}
