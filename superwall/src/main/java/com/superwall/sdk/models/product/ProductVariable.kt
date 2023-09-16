package com.superwall.sdk.models.product

import com.superwall.sdk.misc.JSONObjectSerializer
import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.Serializable
import org.json.JSONObject

@Serializable
data class ProductVariable(
    val type: ProductType,
    @Serializable(with = JSONObjectSerializer::class)
    val attributes: JSONObject
)