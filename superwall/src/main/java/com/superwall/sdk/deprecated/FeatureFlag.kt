//import org.json.JSONArray
//import org.json.JSONObject
//
//data class RawFeatureFlag(
//    val key: String,
//    val enabled: Boolean
//)
//
//data class FeatureFlags(
//    val enableSessionEvents: Boolean,
//    val enablePostback: Boolean
//) {
//    companion object {
//        fun fromJson(jsonArray: JSONArray): FeatureFlags {
//            val rawFeatureFlags = List(jsonArray.length()) { index ->
//                val featureFlagJsonObject = jsonArray.getJSONObject(index)
//                RawFeatureFlag(
//                    key = featureFlagJsonObject.getString("key"),
//                    enabled = featureFlagJsonObject.getBoolean("enabled")
//                )
//            }
//
//            return FeatureFlags(
//                enableSessionEvents = rawFeatureFlags.valueForKey("enable_session_events", false),
//                enablePostback = rawFeatureFlags.valueForKey("enable_postback", false)
//            )
//        }
//    }
//}
//
//fun List<RawFeatureFlag>.valueForKey(key: String, default: Boolean): Boolean {
//    val featureFlag = firstOrNull { it.key == key }
//    return featureFlag?.enabled ?: default
//}
