import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.view.webview.templating.models.ExperimentTemplate
import com.superwall.sdk.paywall.view.webview.templating.models.FreeTrialTemplate
import com.superwall.sdk.paywall.view.webview.templating.models.JsonVariables
import com.superwall.sdk.paywall.view_controller.web_view.templating.models.ProductTemplate
import kotlinx.serialization.json.Json

object TemplateLogic {
    suspend fun getBase64EncodedTemplates(
        json: Json,
        paywall: Paywall,
        event: EventData?,
        factory: VariablesFactory,
    ): String {
        val productsTemplate =
            ProductTemplate(
                eventName = "products",
                products = paywall.playStoreProducts,
            )

        val variablesTemplate =
            factory.makeJsonVariables(
                products = paywall.productVariables,
                computedPropertyRequests = paywall.computedPropertyRequests,
                event = event,
            )

        val freeTrialTemplate =
            FreeTrialTemplate(
                eventName = "template_substitutions_prefix",
                prefix = if (paywall.isFreeTrialAvailable) "freeTrial" else null,
            )
        val experimentTemplate =
            ExperimentTemplate(
                "experiment",
                paywall.experiment?.id ?: "",
                paywall?.experiment?.variant?.id ?: "",
                paywall?.experiment?.groupId ?: "",
            )
//
//        val swProductTemplate = swProductTemplate(
//            swProductTemplateVariables = paywall.swProductVariablesTemplate ?: emptyList()
//        )

        val encodedTemplates =
            listOf(
                json.encodeToString(ProductTemplate.serializer(), productsTemplate),
                json.encodeToString(JsonVariables.serializer(), variablesTemplate),
                json.encodeToString(FreeTrialTemplate.serializer(), freeTrialTemplate),
                json.encodeToString(ExperimentTemplate.serializer(), experimentTemplate),
//            json.encodeToString(swProductTemplate)
            )
        val templatesString = "[" + encodedTemplates.joinToString(",") + "]"
        val templatesData = templatesString.toByteArray(Charsets.UTF_8)

        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
            "!!! Template Logic: $templatesString",
        )

        return android.util.Base64.encodeToString(templatesData, android.util.Base64.NO_WRAP)
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
