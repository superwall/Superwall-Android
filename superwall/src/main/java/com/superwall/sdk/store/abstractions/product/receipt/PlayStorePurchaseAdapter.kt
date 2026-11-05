package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.google.toStorePurchase
import com.superwall.sdk.store.abstractions.product.StoreProduct

/**
 * Adapts a Google Play [Purchase] to [EntitlementTransaction]. Kept for its public signature;
 * it converts the purchase to the neutral form and reads it through [StorePurchaseTransaction].
 */
class PlayStorePurchaseAdapter internal constructor(
    private val transaction: StorePurchaseTransaction,
) : EntitlementTransaction by transaction {
    constructor(
        purchase: Purchase,
        product: StoreProduct?,
        productId: String,
    ) : this(StorePurchaseTransaction(purchase.toStorePurchase(), product, productId))

    companion object {
        /**
         * Creates adapters for all products in a purchase.
         *
         * @param purchase The Google Play purchase
         * @param productsById Map of products keyed by raw product ID (productIdentifier)
         * @return List of adapters, one for each product in the purchase
         */
        fun fromPurchase(
            purchase: Purchase,
            productsById: Map<String, StoreProduct>,
        ): List<PlayStorePurchaseAdapter> =
            StorePurchaseTransaction
                .fromPurchase(purchase.toStorePurchase(), productsById)
                .map(::PlayStorePurchaseAdapter)
    }
}
