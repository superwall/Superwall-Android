package com.superwall.sdk.billing

import android.app.Activity
import com.superwall.sdk.billing.google.PlayPurchases
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.delegate.InternalPurchaseResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.transform

/**
 * A [Store] over a public [Billing], for SDK classes whose public constructors still take one
 * (such as `ReceiptManager`). [GoogleBillingWrapper] already is a store, so [storeOf] hands
 * its own back; any other [Billing], such as a test double, is read through this adapter.
 *
 * [Billing] can't launch purchases or acknowledge, so those fail. It records every Play
 * purchase it converts in [purchases], so the original object can still be found by token.
 */
internal class BillingBackedStore(
    private val billing: Billing,
    val purchases: PlayPurchases = PlayPurchases(),
) : Store {
    override val availability: StateFlow<BillingAvailability> =
        MutableStateFlow<BillingAvailability>(BillingAvailability.Available).asStateFlow()

    override suspend fun products(ids: Set<String>): ProductsResult {
        val found = billing.awaitGetProducts(ids)
        val foundIds = found.map { it.fullIdentifier }.toSet()
        return ProductsResult(found = found, missingIds = ids - foundIds)
    }

    override suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        options: PurchaseOptions,
    ): StorePurchaseResult = StorePurchaseResult.Failed(UnsupportedOperationException("This Billing can't launch purchases"))

    override suspend fun activePurchases(): Result<List<StorePurchase>> = runCatching { ownedPurchases() }

    override suspend fun ownedPurchases(): List<StorePurchase> = purchases.record(billing.queryAllPurchases())

    override suspend fun acknowledge(purchase: StorePurchase): Result<Unit> =
        Result.failure(UnsupportedOperationException("This Billing can't acknowledge purchases"))

    override suspend fun consume(token: String): Result<Unit> = billing.consume(token).map { }

    override suspend fun storefrontCountry(): String? = billing.getStorefrontCountryCode()

    override val externalPurchases: Flow<StorePurchase> =
        billing.purchaseResults
            .filterIsInstance<InternalPurchaseResult.Purchased>()
            .transform { emit(purchases.record(listOf(it.purchase)).first()) }

    companion object {
        /** [billing] as a [Store], with a way to find the Play purchase behind a neutral one. */
        fun storeOf(billing: Billing): Pair<Store, PlayPurchases> =
            if (billing is GoogleBillingWrapper) {
                billing.store to billing.playPurchases
            } else {
                BillingBackedStore(billing).let { it to it.purchases }
            }
    }
}
