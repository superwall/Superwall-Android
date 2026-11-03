package com.superwall.sdk.billing

import android.app.Activity
import com.superwall.sdk.store.ProductReplacement
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The store the SDK sells through, without any store's own types. One implementation per
 * store owns that store's only client; the SDK's receipt, entitlement and transaction code
 * talks to it through this port and the neutral types below.
 */
internal interface Store {
    /** Whether the store can be used on this device. */
    val availability: StateFlow<BillingAvailability>

    /**
     * The products for [ids]. Ids the store doesn't know end up in [ProductsResult.missingIds]
     * instead of failing the request. Throws only when the store can't be asked, such as a
     * [BillingError] or a [BillingError.Timeout].
     */
    suspend fun products(ids: Set<String>): ProductsResult

    /** Launches the store's purchase flow for [product] and waits for its outcome. */
    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        options: PurchaseOptions = PurchaseOptions(),
    ): StorePurchaseResult

    /** The purchases the store reports as owned, subscriptions and one-time products alike. */
    suspend fun activePurchases(): Result<List<StorePurchase>>

    suspend fun acknowledge(purchase: StorePurchase): Result<Unit>

    suspend fun consume(token: String): Result<Unit>

    /** The country of the user's store account, or `null` when it can't be learned. */
    suspend fun storefrontCountry(): String?

    /** Every purchase the store reports to the SDK, including pending ones completing later. */
    val externalPurchases: Flow<StorePurchase>
}

/** The answer to [Store.products]: the products found and the ids the store didn't return. */
internal data class ProductsResult(
    val found: Set<StoreProduct>,
    val missingIds: Set<String>,
)

/** A purchase as the store reports it. */
internal data class StorePurchase(
    val token: String,
    val orderId: String?,
    val productIds: List<String>,
    val purchaseTime: Long,
    val state: StorePurchaseState,
    val autoRenewing: Boolean,
    val acknowledged: Boolean,
)

internal sealed interface StorePurchaseState {
    /** Paid for and owned. */
    object Purchased : StorePurchaseState

    /** Waiting on payment, such as a cash payment or a parent's approval. */
    object Pending : StorePurchaseState

    object Unknown : StorePurchaseState
}

/** How [Store.purchase] should buy the product. */
internal data class PurchaseOptions(
    /** The id that ties the purchase to the user's account in the store. */
    val obfuscatedAccountId: String? = null,
    /** The subscription this purchase replaces, if any. */
    val replacement: ProductReplacement? = null,
)

/** The outcome of a purchase flow. */
internal sealed interface StorePurchaseResult {
    data class Purchased(
        val purchases: List<StorePurchase>,
    ) : StorePurchaseResult

    object Cancelled : StorePurchaseResult

    data class Failed(
        val error: Throwable,
    ) : StorePurchaseResult
}
