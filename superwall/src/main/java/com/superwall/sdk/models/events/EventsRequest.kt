import com.superwall.sdk.models.events.EventData
import org.json.JSONArray
import org.json.JSONObject


@kotlinx.serialization.Serializable
data class EventsRequest(val events: Array<EventData>)
