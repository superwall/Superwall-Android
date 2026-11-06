package com.superwall.sdk.store

import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager
import com.superwall.sdk.utilities.flatten
import com.superwall.sdk.utilities.withErrorTracking

/**
 * The purchase, product, consume and restore calls of the public API, made outside a paywall.
 */
internal class PurchaseFacade(
    private val transactionManager: () -> TransactionManager,
    private val storeManager: () -> StoreManager,
    private val hasInternalPurchaseController: () -> Boolean,
) {
    /** Purchases a [StoreProduct], as an external purchase that dismisses any paywall. */
    suspend fun purchase(product: StoreProduct): Result<PurchaseResult> =
        withErrorTracking {
            purchaseExternally(product)
        }.toResult()

    /** Looks up [productId], then purchases it. Fails when the store has no such product. */
    suspend fun purchase(productId: String): Result<PurchaseResult> =
        withErrorTracking {
            getProducts(listOf(productId)).getOrThrow()[productId]?.let {
                purchaseExternally(it)
            } ?: throw IllegalArgumentException("Product with id $productId not found")
        }.toResult()

    /** Fetches [productIds] from the store, without a paywall. */
    suspend fun getProducts(productIds: List<String>): Result<Map<String, StoreProduct>> =
        withErrorTracking {
            storeManager().getProductsWithoutPaywall(productIds)
        }.toResult()

    /**
     * Consumes an in-app product. When the SDK handles purchases itself, it then restores, so
     * the entitlements drop the consumed product.
     */
    suspend fun consume(purchaseToken: String): Result<String> =
        withErrorTracking {
            val storeManager = storeManager()
            storeManager.consume(purchaseToken).onSuccess {
                if (hasInternalPurchaseController()) {
                    storeManager.purchaseController.restorePurchases()
                }
            }
        }.toResult().flatten()

    /** Restores purchases without a paywall. */
    suspend fun restorePurchases(): Result<RestorationResult> =
        withErrorTracking {
            transactionManager().tryToRestorePurchases(null)
        }.toResult()

    private suspend fun purchaseExternally(product: StoreProduct): PurchaseResult =
        transactionManager().purchase(
            TransactionManager.PurchaseSource.ExternalPurchase(product),
            shouldDismiss = true,
        )
}
