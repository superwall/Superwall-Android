package com.superwall.sdk.store.abstractions.product.receipt

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductsFetcher
import kotlinx.coroutines.*
import java.util.*


data class InAppPurchase(var productIdentifier: String) {}

class ReceiptManager(
    private var delegate: ProductsFetcher?,
//    private val receiptData: () -> ByteArray? = ReceiptLogic::getReceiptData
) {
    var purchasedSubscriptionGroupIds: Set<String>? = null
    private var purchases: MutableSet<InAppPurchase> = mutableSetOf()
    private var receiptRefreshCompletion: ((Boolean) -> Unit)? = null

    @Suppress("RedundantSuspendModifier")
    suspend fun loadPurchasedProducts(): Set<StoreProduct>? = coroutineScope {
//        val hasPurchaseController = Superwall.instance.dependencyContainer.delegateAdapter.hasPurchaseController
//
//        val payload = ReceiptLogic.getPayload(receiptData()) ?: run {
////            if (!hasPurchaseController) {
////                Superwall.instance.subscriptionStatus = SubscriptionStatus.INACTIVE
////            }
//            return@coroutineScope null
//        }
//
//        delegate?.let { delegate ->
//            val localPurchases = payload.purchases
//            this@ReceiptManager.purchases = localPurchases.toMutableSet()
//
//            if (!hasPurchaseController) {
//                val activePurchases = localPurchases.filter { it.isActive }
//                if (activePurchases.isEmpty()) {
//                    Superwall.instance.subscriptionStatus = SubscriptionStatus.INACTIVE
//                } else {
//                    Superwall.instance.subscriptionStatus = SubscriptionStatus.ACTIVE
//                }
//            }
//
//            val purchasedProductIds = localPurchases.map { it.productIdentifier }.toSet()
//
//            try {
//                val products = delegate.products(purchasedProductIds, null)
//                val purchasedSubscriptionGroupIds = mutableSetOf<String>()
//                for (product in products) {
//                    product.subscriptionGroupIdentifier?.let {
//                        purchasedSubscriptionGroupIds.add(it)
//                    }
//                }
//                this@ReceiptManager.purchasedSubscriptionGroupIds = purchasedSubscriptionGroupIds
//                products
//            } catch (e: Throwable) {
//                null
//            }
//        } ?: run {
//            if (!hasPurchaseController) {
//                Superwall.instance.subscriptionStatus = SubscriptionStatus.INACTIVE
//            }
//            return@coroutineScope null
//        }

        // SW-2218
        // https://linear.app/superwall/issue/SW-2218/%5Bandroid%5D-%5Bv0%5D-replace-receipt-validation-with-google-play-billing
        return@coroutineScope emptySet()
    }

    suspend fun refreshReceipt() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message = "Refreshing receipts"
        )

        // Code to refresh the receipt goes here

        // SW-2218
        // https://linear.app/superwall/issue/SW-2218/%5Bandroid%5D-%5Bv0%5D-replace-receipt-validation-with-google-play-billing
    }

    fun hasPurchasedProduct(productId: String): Boolean {
        return purchases.firstOrNull { it.productIdentifier == productId } != null
    }
}

