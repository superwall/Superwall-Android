package com.superwall.sdk.paywall.request

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.store.abstractions.product.StoreProduct

data class ResponseIdentifiers(
    val paywallId: String?,
    var experiment: Experiment? = null,
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
    var isFreeTrialAvailable: Boolean,
)

object PaywallLogic {
    fun requestHash(
        identifier: String? = null,
        event: EventData? = null,
        locale: String,
        joinedSubstituteProductIds: String?,
    ): String {
        val id = identifier ?: event?.name ?: "\$called_manually"

        var substitutions = ""
        joinedSubstituteProductIds?.let {
            substitutions = it
        }

        return "${id}_${locale}_$substitutions"
    }

    fun handlePaywallError(
        error: Throwable,
        event: EventData?,
        trackEvent: (suspend (event: Trackable) -> Result<TrackingResult>)? = null,
    ): Throwable {
        var _trackEvent: (suspend (event: Trackable) -> Result<TrackingResult>)? = trackEvent
        if (_trackEvent == null) {
            _trackEvent = { event: Trackable ->
                Superwall.instance.track(event)
            }
        }

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

    fun getVariablesAndFreeTrial(
        productItems: List<ProductItem>,
        productsByFullId: Map<String, StoreProduct>,
        isFreeTrialAvailableOverride: Boolean?,
        customerInfo: CustomerInfo,
    ): ProductProcessingOutcome {
        val productVariables = mutableListOf<ProductVariable>()
        var hasFreeTrial = false

        for (productItem in productItems) {
            val storeProduct = productsByFullId[productItem.fullProductId]

            if (storeProduct != null) {
                productVariables.add(
                    ProductVariable(
                        name = productItem.name,
                        attributes = storeProduct.attributes,
                    ),
                )
            }

            if (hasFreeTrial) continue

            hasFreeTrial =
                when (val type = productItem.type) {
                    is ProductItem.StoreProductType.PlayStore,
                    is ProductItem.StoreProductType.AppStore,
                    is ProductItem.StoreProductType.Other,
                    -> storeProduct?.hasFreeTrial == true

                    is ProductItem.StoreProductType.Stripe ->
                        (type.product.trialDays ?: 0) > 0 &&
                            !customerInfo.hasEverSubscribedOn(Store.STRIPE)

                    is ProductItem.StoreProductType.Paddle ->
                        (type.product.trialDays ?: 0) > 0 &&
                            !customerInfo.hasEverSubscribedOn(Store.PADDLE)
                }
        }

        // Use the override if it is set
        isFreeTrialAvailableOverride?.let {
            hasFreeTrial = it
        }

        return ProductProcessingOutcome(
            productVariables = productVariables,
            isFreeTrialAvailable = hasFreeTrial,
        )
    }

    private fun CustomerInfo.hasEverSubscribedOn(store: Store): Boolean =
        subscriptions.any { it.store == store }
}
