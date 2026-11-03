package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.google.toStorePurchase
import com.superwall.sdk.store.abstractions.product.StoreProduct

/**
 * Provides subscription status information from the billing service.
 *
 * This interface abstracts the subscription status querying logic,
 * making it easier to test and swap implementations.
 */
interface SubscriptionStatusProvider {
    /**
     * Gets the subscription state for a purchase.
     */
    suspend fun getSubscriptionState(
        purchase: Purchase,
        product: StoreProduct?,
    ): LatestSubscriptionState

    /**
     * Gets whether the subscription will auto-renew.
     */
    fun getWillAutoRenew(purchase: Purchase): Boolean

    /**
     * Gets the offer type (trial, promotional, etc.) for a purchase.
     */
    fun getOfferType(
        purchase: Purchase,
        product: StoreProduct?,
    ): LatestPeriodType?

    /**
     * Determines if a purchase is currently active.
     */
    suspend fun isActive(
        purchase: Purchase,
        product: StoreProduct?,
    ): Boolean
}

/**
 * Default implementation using Google Play Billing. Kept for its public signature; it converts
 * each Play purchase to the neutral form and runs the SDK's own status logic.
 */
class PlayBillingSubscriptionStatusProvider(
    @Suppress("UNUSED_PARAMETER") billing: Billing,
) : SubscriptionStatusProvider {
    override suspend fun getSubscriptionState(
        purchase: Purchase,
        product: StoreProduct?,
    ): LatestSubscriptionState = DefaultSubscriptionStatus.subscriptionState(purchase.toStorePurchase(), product)

    override fun getWillAutoRenew(purchase: Purchase): Boolean = DefaultSubscriptionStatus.willAutoRenew(purchase.toStorePurchase())

    override fun getOfferType(
        purchase: Purchase,
        product: StoreProduct?,
    ): LatestPeriodType? = DefaultSubscriptionStatus.offerType(purchase.toStorePurchase(), product)

    override suspend fun isActive(
        purchase: Purchase,
        product: StoreProduct?,
    ): Boolean = DefaultSubscriptionStatus.isActive(purchase.toStorePurchase(), product)
}
