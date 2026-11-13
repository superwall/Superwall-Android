import com.superwall.sdk.billing.DecomposedProductIds
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.CrossplatformProduct
import com.superwall.sdk.models.product.PlayStoreProduct
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.store.abstractions.product.BasePlanType
import com.superwall.sdk.paywall.view.webview.templating.models.ExperimentTemplate
import com.superwall.sdk.paywall.view.webview.templating.models.FreeTrialTemplate
import com.superwall.sdk.paywall.view.webview.templating.models.JsonVariables
import com.superwall.sdk.paywall.view.webview.templating.models.ProductTemplate
import kotlinx.serialization.json.Json

object TemplateLogic {
    suspend fun getBase64EncodedTemplates(
        json: Json,
        paywall: Paywall,
        event: EventData?,
        factory: VariablesFactory,
        encodeToBase64: (String) -> String,
    ): String {
        val productsTemplate =
            ProductTemplate(
                eventName = "products",
                products = templatedProducts(paywall),
            )

        val variablesTemplate =
            factory.makeJsonVariables(
                products =
                    productVariablesWithSubscribedState(
                        paywall = paywall,
                        activeProductIds = factory.activePurchasedProductIds(),
                    ),
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
        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
        ) { "!!! Template Logic: $templatesString" }

        return encodeToBase64(templatesString)
    }

    /**
     * The paywall's product variables, each Play Store product's with `isSubscribed` set to
     * whether the user has an active purchase of it. A purchase matches a product with the
     * same subscription id, and the same base plan when both name one: a purchase can be
     * recorded without its base plan, and a product can let the SDK pick it.
     */
    internal fun productVariablesWithSubscribedState(
        paywall: Paywall,
        activeProductIds: Set<String>,
    ): List<ProductVariable>? {
        val variables = paywall.productVariables ?: return null
        val active = activeProductIds.map { DecomposedProductIds.from(it) }
        val productsByName =
            paywall.playStoreProducts
                .mapNotNull { product ->
                    (product.storeProduct as? CrossplatformProduct.StoreProduct.PlayStore)
                        ?.let { product.name to it }
                }.toMap()
        return variables.map { variable ->
            val product = productsByName[variable.name] ?: return@map variable
            val basePlanId = BasePlanType.from(product.basePlanIdentifier).specificId
            val isSubscribed =
                active.any {
                    it.subscriptionId == product.productIdentifier &&
                        (basePlanId == null || it.basePlanId == null || it.basePlanId == basePlanId)
                }
            variable.copy(attributes = variable.attributes + ("isSubscribed" to isSubscribed))
        }
    }

    /**
     * The products the paywall page can purchase: Play Store products, plus custom store
     * products so the page learns their ids and can send them back with a purchase.
     */
    internal fun templatedProducts(paywall: Paywall): List<ProductItem> {
        val playStore =
            paywall.playStoreProducts.map {
                val product = it.storeProduct as CrossplatformProduct.StoreProduct.PlayStore
                ProductItem(
                    name = it.name,
                    entitlements = it.entitlements.toSet(),
                    type =
                        ProductItem.StoreProductType.PlayStore(
                            PlayStoreProduct(
                                productIdentifier = product.productIdentifier,
                                basePlanIdentifier = product.basePlanIdentifier,
                                offer = product.offer,
                            ),
                        ),
                    compositeId = it.compositeId,
                )
            }
        // Custom products are cached by their product identifier, so that is the id the
        // page must send back when purchasing one.
        val custom =
            paywall.productItems
                .filter { it.type is ProductItem.StoreProductType.Custom }
                .map { it.copy(compositeId = it.fullProductId) }
        return playStore + custom
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
