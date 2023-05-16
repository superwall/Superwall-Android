package com.superwall.sdk.models.triggers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class VariantOption(
    @SerialName("variant_type")
    val type: Variant.VariantType,
    @SerialName("variant_id")
    val id: String,
    val percentage: Int,
    val paywallId: String? = null
) {
    @Transient
    val variant = toVariant()

    fun toVariant() = Variant(
        id = id,
        type = type,
        paywallId = paywallId
    )

    companion object {
        fun stub() = VariantOption(
            type = Variant.VariantType.TREATMENT,
            id = UUID.randomUUID().toString(),
            percentage = 100,
            paywallId = UUID.randomUUID().toString()
        )
    }
}
