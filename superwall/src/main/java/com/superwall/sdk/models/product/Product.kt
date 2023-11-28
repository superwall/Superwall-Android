package com.superwall.sdk.models.product

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProductType {
    @SerialName("primary")
    PRIMARY,

    @SerialName("secondary")
    SECONDARY,

    @SerialName("tertiary")
    TERTIARY;

    override fun toString() = name.lowercase()
}

@Serializable
data class Product(
    @SerialName("product") val type: ProductType,
    @SerialName("product_id_android") val id: String
)
