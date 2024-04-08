import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.paywall.vc.web_view.templating.models.FreeTrialTemplate
import com.superwall.sdk.paywall.view_controller.web_view.templating.models.ProductTemplate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.*

object TemplateLogic {
    suspend fun getBase64EncodedTemplates(
        paywall: Paywall,
        event: EventData?,
        factory: VariablesFactory
    ): String {
        val productsTemplate = ProductTemplate(
            eventName = "products",
            products = paywall.products
        )

        val variablesTemplate = factory.makeJsonVariables(
            products = paywall.productVariables,
            computedPropertyRequests = paywall.computedPropertyRequests,
            event = event
        )

        val freeTrialTemplate = FreeTrialTemplate(
            eventName = "template_substitutions_prefix",
            prefix = if (paywall.isFreeTrialAvailable) "freeTrial" else null
        )

//
//        val swProductTemplate = swProductTemplate(
//            swProductTemplateVariables = paywall.swProductVariablesTemplate ?: emptyList()
//        )

        val json = Json { encodeDefaults = true }

        val encodedTemplates = listOf(
            json.encodeToString(productsTemplate),
            json.encodeToString(variablesTemplate),
            json.encodeToString(freeTrialTemplate),
//            json.encodeToString(swProductTemplate)
        )

        val templatesString = "[" + encodedTemplates.joinToString(",") + "]"
        val templatesData = templatesString.toByteArray(Charsets.UTF_8)

        println("!!! Template Logic: $templatesString")

        return Base64.getEncoder().encodeToString(templatesData)
    }

//    private fun swProductTemplate(
//        swProductTemplateVariables: List<ProductVariable>
//    ): Json {
//        val variables = mutableMapOf<String, Any>()
//
//        for (variable in swProductTemplateVariables) {
//            variables[variable.type.name] = Json(variable.attributes)
//        }
//
//        val values = mapOf(
//            "event_name" to "template_product_variables",
//            "variables" to variables
//        )
//
//        return Json(values)
//    }
}
