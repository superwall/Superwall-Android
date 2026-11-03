package com.superwall.sdk.billing.google

import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import java.util.concurrent.ConcurrentHashMap

/**
 * The last Play [Purchase] seen for each purchase token, so code at the public edge (a custom
 * `SubscriptionStatusProvider`, `GoogleBillingPurchaseTransaction`) can still be handed the
 * original object for a [StorePurchase]. It belongs to one wrapper instance.
 */
internal class PlayPurchases {
    private val byToken = ConcurrentHashMap<String, Purchase>()

    /** Records [purchases] and returns their neutral form, in the same order. */
    fun record(purchases: List<Purchase>): List<StorePurchase> =
        purchases.map { purchase ->
            byToken[purchase.purchaseToken] = purchase
            purchase.toStorePurchase()
        }

    /** The Play purchase last seen for [token], or `null` if none was. */
    fun native(token: String): Purchase? = byToken[token]

    /** The Play purchases last seen for [purchases], skipping any never seen. */
    fun native(purchases: List<StorePurchase>): List<Purchase> = purchases.mapNotNull { native(it.token) }

    /** The SDK transaction [make] builds from the Play purchase behind [purchase], or `null` if none was seen. */
    suspend fun transactionFor(
        purchase: StorePurchase,
        make: suspend (Purchase) -> StoreTransaction,
    ): StoreTransaction? = native(purchase.token)?.let { make(it) }
}
