package com.superwall.sdk.billing.google

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams
import com.superwall.sdk.billing.BillingAvailability
import com.superwall.sdk.billing.ProductsResult
import com.superwall.sdk.billing.PurchaseOptions
import com.superwall.sdk.billing.PurchaseQueries
import com.superwall.sdk.billing.Store
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/**
 * The [Store] for Google Play, over the queries and connection of one `GoogleBillingWrapper`.
 * Play types stay here and in the conversions; callers see only neutral types.
 *
 * @param purchaseUpdates every outcome Play reports to the wrapper's purchases listener.
 * @param purchases records each Play purchase it converts, by token.
 * @param latestResult the last result the wrapper's purchases listener kept, or `null` once cleared.
 * @param flowParams builds Play's purchase flow params; replaceable in tests, where Play's builder can't run.
 */
internal class GooglePlayStore(
    private val queries: PurchaseQueries,
    override val availability: StateFlow<BillingAvailability>,
    private val purchaseUpdates: SharedFlow<StorePurchaseResult>,
    private val purchases: PlayPurchases,
    private val latestResult: Flow<InternalPurchaseResult?>,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val flowParams: (RawStoreProduct, PurchaseOptions) -> BillingFlowParams = ::billingFlowParamsOf,
) : Store {
    override suspend fun products(ids: Set<String>): ProductsResult = queries.productsResult(ids)

    override suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        options: PurchaseOptions,
    ): StorePurchaseResult {
        val raw =
            product.rawStoreProduct
                ?: return StorePurchaseResult.Failed(IllegalArgumentException("${product.fullIdentifier} is not a Google Play product"))
        return coroutineScope {
            // Listen before launching, so an outcome Play reports straight away isn't missed.
            val outcome = async(start = CoroutineStart.UNDISPATCHED) { purchaseUpdates.first() }
            queries
                .launchBillingFlow(activity, flowParams(raw, options), mainDispatcher)
                .fold(
                    onSuccess = { outcome.await() },
                    onFailure = {
                        outcome.cancel()
                        StorePurchaseResult.Failed(it)
                    },
                )
        }
    }

    override suspend fun activePurchases(): Result<List<StorePurchase>> = queries.activePurchases().map(purchases::record)

    override suspend fun ownedPurchases(): List<StorePurchase> = purchases.record(queries.allPurchases())

    override suspend fun acknowledge(purchase: StorePurchase): Result<Unit> = queries.acknowledge(purchase.token)

    override suspend fun consume(token: String): Result<Unit> = queries.consume(token).map { }

    override suspend fun storefrontCountry(): String? = queries.storefrontCountryCode()

    override val externalPurchases: Flow<StorePurchase> =
        purchaseUpdates
            .filterIsInstance<StorePurchaseResult.Purchased>()
            .transform { result -> result.purchases.forEach { emit(it) } }

    override suspend fun subscriptionPurchases(): Result<List<StorePurchase>> =
        queries.subscriptionPurchases().map(purchases::record)

    override val latestPurchaseUpdate: Flow<StorePurchaseUpdate?> =
        latestResult.map { it?.toStorePurchaseUpdate(purchases) }
}
