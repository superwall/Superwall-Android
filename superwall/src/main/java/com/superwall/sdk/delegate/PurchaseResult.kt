package com.superwall.sdk.delegate

import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransactionType
import java.lang.Exception

sealed class InternalPurchaseResult {
    data class Purchased(val storeTransaction: StoreTransactionType?) : InternalPurchaseResult()
    object Restored : InternalPurchaseResult()
    object Cancelled : InternalPurchaseResult()
    object Pending : InternalPurchaseResult()
    data class Failed(val error: Exception) : InternalPurchaseResult()
}

/**
 * An enum that defines the possible outcomes of attempting to purchase a product.
 *
 * When implementing the `PurchaseController.purchase(product: SkuDetails)` function,
 * all cases should be considered.
 */
sealed class PurchaseResult {
    /**
     * The purchase was cancelled.
     *
     * In the Play Billing Library, you can detect this by the specific error codes during purchase.
     *
     * With RevenueCat, this is when the `userCancelled` boolean returns `true` from the purchase
     * method.
     */
    class Cancelled() : PurchaseResult()

    /** The product was purchased. */
    class Purchased : PurchaseResult()

    /**
     * The purchase is pending and requires action from the developer.
     *
     * In the Play Billing Library, this is the same as the `PENDING` state.
     *
     * With RevenueCat, this is retrieved by checking the specific error during purchase.
     */
    class Pending : PurchaseResult()

    /**
     * The purchase failed for a reason other than the user cancelling or the payment pending.
     *
     * Send the `Exception` back to the relevant method to alert the user.
     */
    data class Failed(val error: Exception) : PurchaseResult()

    override fun equals(other: Any?): Boolean {
        return when {
            this is Cancelled && other is Cancelled -> true
            this is Purchased && other is Purchased -> true
            this is Pending && other is Pending -> true
            this is Failed && other is Failed -> this.error.message == other.error.message
            else -> false
        }
    }
}

