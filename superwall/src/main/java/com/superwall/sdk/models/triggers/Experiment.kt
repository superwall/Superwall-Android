package com.superwall.sdk.models.triggers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Variant(
    val id: String,

//    @Serializable(with = VariantTypeSerializer::class)
    val type: VariantType,

    @SerialName("paywall_identifier")
    val paywallId: String?
) {
    enum class VariantType {
        TREATMENT,
        HOLDOUT
    }
}

@Serializable
data class Experiment(
    @SerialName("experiment_id")
    val id: String,

    @SerialName("trigger_experiment_group_id")
    val groupId: String,

//    @Serializable(with = VariantSerializer::class)
    val variant: Variant
) {
    companion object {
        fun presentById(id: String) = Experiment(
            id = id,
            groupId = "",
            variant = Variant(id = "", type = Variant.VariantType.TREATMENT, paywallId = id)
        )

        fun stub() = Experiment(
            id = "a",
            groupId = "b",
            variant = Variant(
                id = "c",
                type = Variant.VariantType.TREATMENT,
                paywallId = "d"
            )
        )
    }
}