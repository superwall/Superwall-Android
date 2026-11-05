package com.superwall.sdk.billing

import com.android.billingclient.api.Purchase
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.flow.MutableStateFlow

interface Billing {
    val purchaseResults: MutableStateFlow<InternalPurchaseResult?>

    suspend fun awaitGetProducts(identifiers: Set<String>): Set<StoreProduct>

    suspend fun getLatestTransaction(factory: StoreTransactionFactory): StoreTransaction?

    suspend fun queryAllPurchases(): List<Purchase>

    /**
     * Every purchase Play returns, or a failure when Play couldn't be asked (billing not ready,
     * a query timed out or its retries ran out). Unlike [queryAllPurchases], a failed read is
     * never mistaken for "no purchases".
     */
    suspend fun queryAllPurchasesResult(): Result<List<Purchase>> = runCatching { queryAllPurchases() }

    /** Fails when Play can't be asked, so a failure is never mistaken for "no subscriptions". */
    suspend fun querySubscriptionPurchases(): Result<List<Purchase>>

    suspend fun consume(purchaseToken: String): Result<String>

    suspend fun getStorefrontCountryCode(): String?
}
