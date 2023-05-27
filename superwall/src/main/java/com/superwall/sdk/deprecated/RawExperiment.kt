//import com.superwall.sdk.api.VariantOption
//import org.json.JSONObject
//
//data class RawExperiment(
//    val id: String,
//    val groupId: String,
//    val variants: List<VariantOption>
//) {
//    companion object {
//        fun fromJson(jsonObject: JSONObject): RawExperiment {
//            val id = jsonObject.getString("id")
//            val groupId = jsonObject.getString("group_id")
//            val variantsJsonArray = jsonObject.getJSONArray("variants")
//            val variants = List(variantsJsonArray.length()) { index ->
//                VariantOption.fromJson(variantsJsonArray.getJSONObject(index))
//            }
//
//            return RawExperiment(id, groupId, variants)
//        }
//    }
//}
