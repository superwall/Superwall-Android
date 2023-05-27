package com.superwall.sdk.paywall.request

import com.superwall.sdk.models.triggers.Experiment

data class ResponseIdentifiers(
    val paywallId: String?,
    var experiment: Experiment?
) {
    companion object {
        val none: ResponseIdentifiers
            get() = ResponseIdentifiers(paywallId = null, experiment = null)
    }
}
//
//data class ProductProcessingOutcome(
//    var productVariables: List<ProductVariable>,
//    var swProductVariablesTemplate: List<ProductVariable>,
//    var orderedSwProducts: List<SWProduct>,
//    var isFreeTrialAvailable: Boolean
//)
//
//object PaywallLogic {
//    fun requestHash(
//        identifier: String? = null,
//        event: EventData? = null,
//        locale: String,
//        paywallProducts: PaywallProducts?
//    ): String {
//        val id = identifier ?: event?.name ?: "\$called_manually"
//        val substitutions = paywallProducts?.ids?.joinToString(separator = "") ?: ""
//        return "$id_$locale_$substitutions"
//    }
//
//    fun handlePaywallError(
//        error: Error,
//        event: EventData?,
//        trackEvent: suspend (Trackable) -> TrackingResult = Superwall.shared.track
//    ): NSError {
//        if (error is CustomURLSession.NetworkError && error == .notFound) {
//            val trackedEvent = InternalSuperwallEvent.PaywallLoad(
//                state = .notFound,
//            eventData = event
//            )
//            GlobalScope.launch {
//                trackEvent(trackedEvent)
//            }
//        } else {
//            val trackedEvent = InternalSuperwallEvent.PaywallLoad(
//                state = .fail,
//            eventData = event
//            )
//            GlobalScope.launch {
//                trackEvent(trackedEvent)
//            }
//        }
//
//        val userInfo = mapOf(
//            NSLocalizedDescriptionKey to NSLocalizedString(
//                key = "Not Found",
//                value = "There isn't a paywall configured to show in this context",
//                comment = ""
//            )
//        )
//        return NSError(
//            domain = "SWPaywallNotFound",
//            code = 404,
//            userInfo = userInfo
//        )
//    }
//
//    suspend fun getVariablesAndFreeTrial(
//        products: List<Product>,
//        productsById: Map<String, StoreProduct>,
//        isFreeTrialAvailableOverride: Boolean?,
//        isFreeTrialAvailable: suspend (StoreProduct) -> Boolean
//    ): ProductProcessingOutcome {
//        val productVariables = mutableListOf<ProductVariable>()
//        val swTemplateProductVariables = mutableListOf<ProductVariable>()
//        var hasFreeTrial = false
//        val orderedSwProducts = mutableListOf<SWProduct>()
//
//        for (product in products) {
//            // Get storeProduct
//            val storeProduct = productsById[product.id] ?: continue
//            orderedSwProducts.add(storeProduct.swProduct)
//
//            val productVariable = ProductVariable(
//                type = product.type,
//                attributes = storeProduct.attributesJson
//            )
//            productVariables.add(productVariable)
//
//            val swTemplateProductVariable = ProductVariable(
//                type = product.type,
//                attributes = storeProduct.swProductTemplateVariablesJson
//            )
//            swTemplateProductVariables.add(swTemplateProductVariable)
//
//            if (!hasFreeTrial) {
//                hasFreeTrial = isFreeTrialAvailable(storeProduct)
//            }
//        }
//
//        // Use the override if it is set
//        isFreeTrialAvailableOverride?.let {
//            hasFreeTrial = it
//        }
//
//        return ProductProcessingOutcome(
//            productVariables = productVariables,
//            swProductVariablesTemplate = swTemplateProductVariables,
//            orderedSwProducts = orderedSwProducts,
//            isFreeTrialAvailable = hasFreeTrial
//        )
//    }
//}
