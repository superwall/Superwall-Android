package com.superwall.sdk.billing.google

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.billing.toInternalResult
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.ObservedReport

/** The neutral form of a Play [Purchase]. */
internal fun Purchase.toStorePurchase(): StorePurchase =
    StorePurchase(
        token = purchaseToken,
        orderId = orderId,
        productIds = products,
        purchaseTime = purchaseTime,
        state = purchaseStateOf(purchaseState),
        autoRenewing = isAutoRenewing,
        acknowledged = isAcknowledged,
    )

/** Maps Play's [Purchase.PurchaseState] value to the neutral state. */
internal fun purchaseStateOf(playState: Int): StorePurchaseState =
    when (playState) {
        Purchase.PurchaseState.PURCHASED -> StorePurchaseState.Purchased
        Purchase.PurchaseState.PENDING -> StorePurchaseState.Pending
        else -> StorePurchaseState.Unknown
    }

/**
 * The neutral outcome of a purchase flow, from what Play reported to its purchases listener.
 * A failure carries the response code as its message, as the wrapper's results always have.
 */
internal fun purchaseResultOf(
    result: BillingResult,
    purchases: List<Purchase>?,
): StorePurchaseResult =
    when {
        result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null ->
            StorePurchaseResult.Purchased(purchases.map { it.toStorePurchase() })
        result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> StorePurchaseResult.Cancelled
        else -> StorePurchaseResult.Failed(Exception(result.responseCode.toString()))
    }

/**
 * The neutral form of a purchase listener result. A purchase is recorded in [purchases] when
 * given, so the original Play object can still be found by its token.
 */
internal fun InternalPurchaseResult.toStorePurchaseUpdate(purchases: PlayPurchases? = null): StorePurchaseUpdate =
    when (this) {
        is InternalPurchaseResult.Purchased ->
            StorePurchaseUpdate.Purchased(
                purchases?.record(listOf(purchase))?.first() ?: purchase.toStorePurchase(),
            )
        InternalPurchaseResult.Cancelled -> StorePurchaseUpdate.Cancelled
        InternalPurchaseResult.Pending -> StorePurchaseUpdate.Pending
        InternalPurchaseResult.Restored -> StorePurchaseUpdate.Restored
        is InternalPurchaseResult.Failed -> StorePurchaseUpdate.Failed(error)
    }

/** The updates a purchase the app made itself reports: one per purchase, or one cancel or failure. */
internal fun PurchasingObserverState.PurchaseResult.toStorePurchaseUpdates(): List<StorePurchaseUpdate> =
    (result to purchases).toInternalResult().map { it.toStorePurchaseUpdate() }

/** What the app reported about its own purchase, in neutral form. */
internal fun PurchasingObserverState.toObservedReport(): ObservedReport? =
    when (this) {
        is PurchasingObserverState.PurchaseResult -> ObservedReport.Result(purchases?.map { it.toStorePurchase() })
        is PurchasingObserverState.PurchaseError ->
            ObservedReport.Error(error, StoreProduct(RawStoreProduct.from(product)))
        is PurchasingObserverState.PurchaseWillBegin -> null
    }
