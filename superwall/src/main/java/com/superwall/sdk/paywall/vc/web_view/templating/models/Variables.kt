package com.superwall.sdk.paywall.vc.web_view.templating.models

import com.superwall.sdk.misc.toMap
import com.superwall.sdk.models.product.ProductType
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.json.JSONObject

data class Variables(
    val user: JSONObject,
    val device: JSONObject,
    val params: JSONObject,
    var primary: JSONObject = JSONObject(),
    var secondary: JSONObject = JSONObject(),
    var tertiary: JSONObject = JSONObject()
) {
    constructor(
        productVariables: List<ProductVariable>?,
        params: JSONObject?,
        userAttributes: Map<String, Any>,
        templateDeviceDictionary: Map<String, Any>?
    ) : this(
        user = JSONObject(userAttributes),
        device = JSONObject(templateDeviceDictionary ?: emptyMap<String, Any>()),
        params = params ?: JSONObject()
    ) {
        productVariables?.forEach { productVariable ->
            when (productVariable.type) {
                ProductType.PRIMARY -> primary = productVariable.attributes
                ProductType.SECONDARY -> secondary = productVariable.attributes
                ProductType.TERTIARY -> tertiary = productVariable.attributes
            }
        }
    }

    fun templated(): JSONObject {
        val template = JSONObject()
        template.put("event_name", "template_variables")

        val dict = dictionary() ?: emptyMap<String, Any>()
        template.put("variables", dict)

        return template
    }

    private fun dictionary(): Map<String, Any?>? {
        return mapOf(
            "user" to user.toMap(),
            "device" to device.toMap(),
            "params" to params.toMap(),
            "primary" to primary.toMap(),
            "secondary" to secondary.toMap(),
            "tertiary" to tertiary.toMap()
        )
    }
}