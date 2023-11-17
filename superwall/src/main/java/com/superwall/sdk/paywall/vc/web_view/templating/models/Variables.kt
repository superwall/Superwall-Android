package com.superwall.sdk.paywall.vc.web_view.templating.models

import com.superwall.sdk.models.product.ProductType
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Variables(
    val user: Map<String, @Serializable(with = AnySerializer::class) Any?>,
    val device: Map<String, @Serializable(with = AnySerializer::class) Any?>,
    val params: Map<String, @Serializable(with = AnySerializer::class) Any?>,
    var primary: Map<String, @Serializable(with = AnySerializer::class) Any?> = emptyMap(),
    var secondary: Map<String, @Serializable(with = AnySerializer::class) Any?> = emptyMap(),
    var tertiary: Map<String, @Serializable(with = AnySerializer::class) Any?> = emptyMap()
) {
    constructor(
        productVariables: List<ProductVariable>?,
        params: Map<String, Any?>?,
        userAttributes: Map<String, Any?>,
        templateDeviceDictionary: Map<String, Any?>?
    ) : this(
        user = userAttributes,
        device = templateDeviceDictionary ?: emptyMap(),
        params = params ?: emptyMap()
    ) {
        productVariables?.forEach { productVariable ->
            when (productVariable.type) {
                ProductType.PRIMARY -> primary = productVariable.attributes
                ProductType.SECONDARY -> secondary = productVariable.attributes
                ProductType.TERTIARY -> tertiary = productVariable.attributes
            }
        }
    }

    fun templated(): JsonVariables {
        return JsonVariables(
            eventName = "template_variables",
            variables = this
        )
    }
}

@Serializable
data class JsonVariables (
    @SerialName("event_name")
    val eventName: String,
    val variables: Variables
)