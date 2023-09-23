package com.superwall.sdk.models.product

import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.Serializable

@Serializable
data class ProductVariable(
    val type: ProductType,
    val attributes: Map<String, @Serializable(with = AnySerializer::class) Any>
)