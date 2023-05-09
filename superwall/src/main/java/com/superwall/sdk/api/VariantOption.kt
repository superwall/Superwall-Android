package com.superwall.sdk.api

import org.json.JSONObject

data class VariantOption(
    val type: Experiment.Variant.VariantType,
    val id: String,
    val percentage: Int,
    val paywallId: String?
) {
    companion object {
        fun fromJson(jsonObject: JSONObject): VariantOption {
            val type = Experiment.Variant.VariantType.valueOf(jsonObject.getString("variant_type"))
            val id = jsonObject.getString("variant_id")
            val percentage = jsonObject.getInt("percentage")
            val paywallId = jsonObject.optString("paywall_identifier", null)
            return VariantOption(type, id, percentage, paywallId)
        }
    }

    fun toVariant(): Experiment.Variant {
        return Experiment.Variant(
            id = id,
            type = type,
            paywallId = paywallId
        )
    }
}
