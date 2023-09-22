package com.superwall.sdk.paywall.request

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.PaywallProducts
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.from
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

data class ResponseIdentifiers(
    val paywallId: String?,
    var experiment: Experiment?
) {
    companion object {
        val none: ResponseIdentifiers
            get() = ResponseIdentifiers(paywallId = null, experiment = null)
    }
}

data class ProductProcessingOutcome(
    var productVariables: List<ProductVariable>,
//    var swProductVariablesTemplate: List<ProductVariable>,
//    var orderedSwProducts: List<SWProduct>,
    var isFreeTrialAvailable: Boolean
)

object PaywallLogic {
    fun requestHash(
        identifier: String? = null,
        event: EventData? = null,
        locale: String,
        paywallProducts: PaywallProducts?
    ): String {
        val id = identifier ?: event?.name ?: "\$called_manually"
        val substitutions = paywallProducts?.ids?.joinToString(separator = "") ?: ""
        return "${id}_${locale}_${substitutions}"
    }

    fun handlePaywallError(
        error: Throwable,
        event: EventData?,
        trackEvent: (suspend (event: Trackable) -> TrackingResult)? = null
    ): Throwable {

        var _trackEvent: (suspend (event: Trackable) -> TrackingResult)? = trackEvent
        if (_trackEvent == null) {
            _trackEvent = { event: Trackable ->
                Superwall.instance.track(event)
            }
        }

        // TODO: Handle the error

//        if (error is CustomURLSession.NetworkError && error == .notFound) {
//        if (error is)
//            val trackedEvent = InternalSuperwallEvent.PaywallLoad(
//                state = .notFound,
//                eventData = event
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
        return java.lang.Exception("Not Found")
    }

    suspend fun getVariablesAndFreeTrial(
        products: List<Product>,
        productsById: Map<String, StoreProduct>,
        isFreeTrialAvailableOverride: Boolean?,
        isFreeTrialAvailable: suspend (StoreProduct) -> Boolean
    ): ProductProcessingOutcome {
        val productVariables = mutableListOf<ProductVariable>()
        val swTemplateProductVariables = mutableListOf<ProductVariable>()
        var hasFreeTrial = false

        for (product in products) {
            // Get storeProduct
            val storeProduct = productsById[product.id] ?: continue

            val productVariable = ProductVariable(
                type = product.type,
                attributes = JsonObject.from(storeProduct.attributes)
            )
            productVariables.add(productVariable)

//            val swTemplateProductVariable = ProductVariable(
//                type = product.type,
//                attributes = storeProduct.swProductTemplateVariablesJson
//            )
//            swTemplateProductVariables.add(swTemplateProductVariable)

            if (!hasFreeTrial) {
                hasFreeTrial = isFreeTrialAvailable(storeProduct)
            }
        }

        // Use the override if it is set
        isFreeTrialAvailableOverride?.let {
            hasFreeTrial = it
        }

        return ProductProcessingOutcome(
            productVariables = productVariables,
//            swProductVariablesTemplate = swTemplateProductVariables,
            isFreeTrialAvailable = hasFreeTrial
        )
    }
}
