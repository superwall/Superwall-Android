package com.superwall.sdk.store.abstractions.product.receipt

import android.content.Context
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.coordinator.ProductsFetcher


// SW-2218
// https://linear.app/superwall/issue/SW-2218/%5Bandroid%5D-%5Bv0%5D-replace-receipt-validation-with-google-play-billing

class ReceiptManager(
    private var context: Context,
    private var delegate: ProductsFetcher
)  {
    private var purchases: MutableSet<StoreTransaction> = mutableSetOf()

    suspend fun loadPurchasedProducts(): Set<StoreTransaction>? {
        var purchasedProducts = delegate.purchasedProducts()
        this@ReceiptManager.purchases = purchasedProducts.toMutableSet()
        return purchasedProducts
    }

    fun isFreeTrialAvailable(product: StoreProduct): Boolean {
        if (!product.hasFreeTrial) {
            return false
        }
        // No subscription groups in Google Play  ¯\_(ツ)_/¯
//        return if (product.subscriptionGroupIdentifier != null && purchasedSubscriptionGroupIds != null) {
//            !purchasedSubscriptionGroupIds!!.contains(product.subscriptionGroupIdentifier)
//        } else {
        return !hasPurchasedProduct(product.productIdentifier)
    }


    fun hasPurchasedProduct(productId: String): Boolean {
        return purchases.firstOrNull { it.productIdentifier == productId } != null
    }
}

