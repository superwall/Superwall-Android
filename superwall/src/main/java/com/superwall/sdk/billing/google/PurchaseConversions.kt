package com.superwall.sdk.billing.google

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseState

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
