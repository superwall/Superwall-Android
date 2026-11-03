package com.superwall.sdk.store.transactions

import com.android.billingclient.api.ProductDetails

/**
 * Purchases started outside the SDK that it observes until Play reports their result.
 *
 * Order matters: a cancel or failure resolves the most recent one, so [observed] is a list,
 * oldest first. Owned by the observer-mode actor; its reducers and actions arrive with it.
 */
internal data class ObserverModeState(
    val observed: List<ObservedPurchase> = emptyList(),
) {
    val latest: ObservedPurchase?
        get() = observed.lastOrNull()
}

/** One observed purchase: the product the app is buying and its Play details. */
internal data class ObservedPurchase(
    val productId: String,
    val details: ProductDetails,
)
