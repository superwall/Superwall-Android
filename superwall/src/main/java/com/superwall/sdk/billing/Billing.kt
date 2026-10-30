package com.superwall.sdk.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.flow.MutableStateFlow

/** What Play reported for one billing flow. */
class PurchaseFlowResult(
    val billingResult: BillingResult,
    val purchases: List<Purchase> = emptyList(),
)

interface Billing {
    /**
     * Every purchase update Play reports to the app, including ones from billing flows we
     * didn't launch (observer mode, promo codes, pending purchases settling).
     */
    val purchaseResults: MutableStateFlow<InternalPurchaseResult?>

    suspend fun awaitGetProducts(identifiers: Set<String>): Set<StoreProduct>

    suspend fun getLatestTransaction(factory: StoreTransactionFactory): StoreTransaction?

    suspend fun queryAllPurchases(): List<Purchase>

    /** Fails when Play can't be asked, so a failure is never mistaken for "no subscriptions". */
    suspend fun querySubscriptionPurchases(): Result<List<Purchase>>

    /** Purchases of one [BillingClient.ProductType], waiting for a connection first. */
    suspend fun queryPurchasesOfType(productType: String): Result<List<Purchase>> =
        Result.failure(UnsupportedOperationException("queryPurchasesOfType is not supported"))

    /**
     * Launches the Play purchase flow and suspends until Play reports the outcome of this flow.
     * Fails (without launching) when billing can't be connected or another flow is in progress.
     */
    suspend fun launchBillingFlow(
        activity: Activity,
        params: BillingFlowParams,
    ): PurchaseFlowResult =
        PurchaseFlowResult(
            BillingResult
                .newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)
                .setDebugMessage("launchBillingFlow is not supported")
                .build(),
        )

    suspend fun acknowledge(purchaseToken: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("acknowledge is not supported"))

    suspend fun consume(purchaseToken: String): Result<String>

    suspend fun getStorefrontCountryCode(): String?

    /** Forgets cached products (and remembered misses) so the next load asks the store again. */
    fun clearProductsCache() {}
}
