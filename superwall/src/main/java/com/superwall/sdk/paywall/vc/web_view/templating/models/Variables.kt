package com.superwall.sdk.paywall.vc.web_view.templating.models

import com.superwall.sdk.models.product.ProductType
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.models.serialization.jsonStringToDictionary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        val variables = this.toDictionary()

        return JsonVariables(
            eventName = "template_variables",
            variables = this
        )
    }

    fun toDictionary(): Map<String, Any> {
        val json = Json { encodeDefaults = true }
        val jsonString = json.encodeToString(this)
        val dictionary = jsonString.jsonStringToDictionary()
        return dictionary
    }
}

@Serializable
data class JsonVariables (
    @SerialName("event_name")
    val eventName: String,
    val variables: Variables
)