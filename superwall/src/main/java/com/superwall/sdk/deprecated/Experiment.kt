//import org.json.JSONObject
//
//class Experiment(
//    val id: String,
//    val groupId: String,
//    val variant: Variant
//) {
//    data class Variant(
//        val id: String,
//        val type: VariantType,
//        val paywallId: String?
//    ) {
//        enum class VariantType {
//            TREATMENT, HOLDOUT;
//
//            companion object {
//                fun fromString(value: String): VariantType {
//                    return when (value.toUpperCase()) {
//                        "TREATMENT" -> TREATMENT
//                        "HOLDOUT" -> HOLDOUT
//                        else -> TREATMENT
//                    }
//                }
//            }
//        }
//    }
//
//    companion object {
//        fun fromJson(jsonString: String): Experiment {
//            val jsonObject = JSONObject(jsonString)
//            val id = jsonObject.getString("experiment_id")
//            val groupId = jsonObject.getString("trigger_experiment_group_id")
//            val variantId = jsonObject.getString("variant_id")
//            val variantType = Variant.VariantType.fromString(jsonObject.getString("variant_type"))
//            val paywallId = jsonObject.optString("paywall_identifier", null)
//
//            val variant = Variant(variantId, variantType, paywallId)
//            return Experiment(id, groupId, variant)
//        }
//
//        fun presentById(id: String): Experiment {
//            return Experiment(
//                id = id,
//                groupId = "",
//                variant = Variant(id = "", type = Variant.VariantType.TREATMENT, paywallId = id)
//            )
//        }
//    }
//}
