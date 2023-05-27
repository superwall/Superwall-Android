//import org.json.JSONArray
//import org.json.JSONObject
//
//data class Trigger(
//    val eventName: String,
//    val rules: List<TriggerRule>
//) {
//    companion object {
//        fun fromJson(jsonObject: JSONObject): Trigger {
//            val eventName = jsonObject.getString("event_name")
//            val rulesJsonArray = jsonObject.getJSONArray("rules")
//            val rules = List(rulesJsonArray.length()) { index ->
//                TriggerRule.fromJson(rulesJsonArray.getJSONObject(index))
//            }
//
//            return Trigger(eventName, rules)
//        }
//    }
//}
