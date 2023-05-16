package com.superwall.sdk.analytics.session

import org.json.JSONObject
import java.util.*

data class AppSession(
    var id: String = UUID.randomUUID().toString(),
    var startAt: Long = System.currentTimeMillis(),
    var endAt: Long? = null
) {
    fun toJson(): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("app_session_id", id)
        jsonObject.put("app_session_start_ts", startAt)
        endAt?.let {
            jsonObject.put("app_session_end_ts", it)
        }
        return jsonObject
    }

    companion object {
        fun fromJson(json: JSONObject): AppSession {
            val id = json.getString("app_session_id")
            val startAt = json.getLong("app_session_start_ts")
            val endAt = if (json.has("app_session_end_ts")) json.getLong("app_session_end_ts") else null
            return AppSession(id, startAt, endAt)
        }

        fun stub() = AppSession()
    }
}
