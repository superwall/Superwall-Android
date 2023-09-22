package com.superwall.sdk.paywall.vc.web_view.templating.models

import com.superwall.sdk.models.product.ProductType
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.AnySerializer
import org.json.JSONObject

@kotlinx.serialization.Serializable
class OuterVariables(
    val event_name: String = "template_variables",
    val variables: Variables
)

@kotlinx.serialization.Serializable
class Variables(
    val user: Map<String, @kotlinx.serialization.Serializable(with = AnySerializer::class) Any?>,
    val primary: Map<String, @kotlinx.serialization.Serializable(with = AnySerializer::class) Any?>?,
    val secondary: Map<String, @kotlinx.serialization.Serializable(with = AnySerializer::class) Any?>?,
    val tertiary: Map<String, @kotlinx.serialization.Serializable(with = AnySerializer::class) Any?>?,
//    val device: Map<String, @kotlinx.serialization.Serializable(with = AnySerializer::class) Any?>,
    val device: DeviceTemplate,
    val params: Map<String, @kotlinx.serialization.Serializable(with = AnySerializer::class) Any?>,
) {

    companion object {
        fun fromProperties(
            productVariables: List<ProductVariable>,
            params: Map<String, Any?>,
            userAttributes: Map<String, Any?>,
            templateDeviceDictionary: DeviceTemplate
        ): OuterVariables {
            val primary = productVariables.firstOrNull { it.type == ProductType.PRIMARY }
            val secondary = productVariables.firstOrNull { it.type == ProductType.SECONDARY }
            val tertiary = productVariables.firstOrNull { it.type == ProductType.TERTIARY }

            return OuterVariables(
                variables = Variables(
                    user = userAttributes,
                    primary = primary?.attributes,
                    secondary = secondary?.attributes,
                    tertiary = tertiary?.attributes,
                    device = templateDeviceDictionary,
                    params = params
                ),
                event_name = "template_variables"
            )
        }
    }
}