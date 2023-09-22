package com.superwall.sdk.models.product

import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

@Serializable
data class ProductVariable(
    val type: ProductType,
    val attributes: JsonObject
)