package com.superwall.sdk.billing

import com.superwall.sdk.store.abstractions.product.OfferType

data class DecomposedProductIds(
    val subscriptionId: String,
    val basePlanId: String?,
    val offerType: OfferType?,
    val fullId: String
) {
    companion object {
        fun from(productId: String): DecomposedProductIds {
            val components = productId.split(":")
            val subscriptionId = components.getOrNull(0) ?: ""
            val basePlanId = components.getOrNull(1)
            val offerId = components.getOrNull(2)
            var offerType: OfferType? = null

            if (offerId == "sw-auto") {
                offerType = OfferType.Auto
            } else if (offerId != null) {
                offerType = OfferType.Offer(id = offerId)
            }
            return DecomposedProductIds(
                subscriptionId = subscriptionId,
                basePlanId = basePlanId,
                offerType = offerType,
                fullId = productId
            )
        }
    }
}