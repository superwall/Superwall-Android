//import com.superwall.sdk.api.VariantOption
//import org.json.JSONObject

//data class TriggerRule(
//    val experiment: RawExperiment,
//    val expression: String?,
//    val expressionJs: String?,
//    val occurrence: TriggerRuleOccurrence?
//) {
//    companion object {
//        fun fromJson(jsonObject: JSONObject): TriggerRule {
//
//            val experimentId = jsonObject.getString("experiment_id")
//            val experimentGroupId = jsonObject.getString("experiment_group_id")
//            val variantsJsonArray = jsonObject.getJSONArray("variants")
//            val variants = List(variantsJsonArray.length()) { index ->
//                VariantOption.fromJson(variantsJsonArray.getJSONObject(index))
//            }
//
//            val experiment = RawExperiment(
//                id = experimentId,
//                groupId = experimentGroupId,
//                variants = variants
//            )
//
//            val expression = jsonObject.optString("expression", null)
//            val expressionJs = jsonObject.optString("expression_js", null)
//            val occurrenceJson = jsonObject.optJSONObject("occurrence")
//            val occurrence = occurrenceJson?.let { TriggerRuleOccurrence.fromJson(it) }
//
//            return TriggerRule(experiment, expression, expressionJs, occurrence)
//        }
//    }
//}
