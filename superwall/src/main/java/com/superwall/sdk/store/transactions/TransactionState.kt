package com.superwall.sdk.store.transactions

import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource

/**
 * State of the purchases the SDK has started and not yet resolved.
 *
 * Owned by the purchase actor; [inFlight] is keyed by the product's full identifier.
 * Its reducers and actions arrive with the purchase flow.
 */
internal data class TransactionState(
    val inFlight: Map<String, InFlightPurchase> = emptyMap(),
) {
    val isPurchasing: Boolean
        get() = inFlight.values.any { it is InFlightPurchase.Purchasing }
}

/** One purchase the SDK is waiting on, typed by where it stands. */
internal sealed class InFlightPurchase {
    abstract val product: StoreProduct
    abstract val source: PurchaseSource

    /** The store's purchase sheet is open, or the purchase controller is still working. */
    data class Purchasing(
        override val product: StoreProduct,
        override val source: PurchaseSource,
    ) : InFlightPurchase()

    /** The store accepted the purchase but holds it for approval, such as a parent's. */
    data class Pending(
        override val product: StoreProduct,
        override val source: PurchaseSource,
    ) : InFlightPurchase()
}
