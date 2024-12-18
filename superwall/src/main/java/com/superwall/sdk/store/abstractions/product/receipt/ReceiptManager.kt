package com.superwall.sdk.store.abstractions.product.receipt

import com.superwall.sdk.billing.Billing
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductsFetcher
import kotlinx.coroutines.*
import java.util.*

data class InAppPurchase(
    var productIdentifier: String,
)

class ReceiptManager(
    private var delegate: ProductsFetcher?,
    private val billing: Billing,
//    private val receiptData: () -> ByteArray? = ReceiptLogic::getReceiptData
) {
    var purchasedSubscriptionGroupIds: Set<String>? = null
    private var _purchases: MutableSet<InAppPurchase> = mutableSetOf()
    private var receiptRefreshCompletion: ((Boolean) -> Unit)? = null

    val purchases: Set<String>
        get() = _purchases.map { it.productIdentifier }.toSet()

    @Suppress("RedundantSuspendModifier")
    suspend fun loadPurchasedProducts(): Set<StoreProduct>? =
        billing
            .queryAllPurchases()
            .flatMap { it.products }
            .let { products ->
                _purchases.addAll(products.map { InAppPurchase(it) }.toSet())
                delegate?.products(products.toSet())
            }?.toSet()

    suspend fun refreshReceipt() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message = "Refreshing receipts",
        )

        // Code to refresh the receipt goes here

        // SW-2218
        // https://linear.app/superwall/issue/SW-2218/%5Bandroid%5D-%5Bv0%5D-replace-receipt-validation-with-google-play-billing
    }

    fun hasPurchasedProduct(productId: String): Boolean = _purchases.firstOrNull { it.productIdentifier == productId } != null
}
