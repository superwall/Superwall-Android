package com.superwall.sdk.paywall.view_controller.web_view.templating.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TODO: Make this use the right product
@Serializable
data class Product(
    val product: String,
)

@Serializable
data class ProductTemplate(
    @SerialName("event_name")
    val eventName: String,
    val products: List<Product>
)
