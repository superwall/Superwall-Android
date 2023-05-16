import com.superwall.sdk.models.events.EventData
import org.json.JSONArray
import org.json.JSONObject


data class EventsRequest(private val events: Array<EventData>) {
    fun toJson(): JSONObject {
        val jsonArray = JSONArray()
        events.forEach { eventData ->
            jsonArray.put(eventData.toJson())
        }
        return JSONObject().put("events", jsonArray)
    }
}
