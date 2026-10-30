package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
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
 * Default implementation using Google Play Billing.
 */
class PlayBillingSubscriptionStatusProvider(
    private val billing: Billing,
) : SubscriptionStatusProvider {
    /**
     * A purchase returned by `queryPurchases` is entitled now, so PURCHASED means SUBSCRIBED.
     * Play Billing has no client-side expiry, grace or hold signal, so we don't try to derive one
     * from `purchaseTime` (which stays at the original purchase across renewals).
     * When there's no current purchase at all, see [ReceiptManager.lastSubscriptionProperties].
     */
    override suspend fun getSubscriptionState(
        purchase: Purchase,
        product: StoreProduct?,
    ): LatestSubscriptionState =
        when (purchase.purchaseState) {
            PurchaseState.PURCHASED -> LatestSubscriptionState.SUBSCRIBED
            PurchaseState.UNSPECIFIED_STATE -> LatestSubscriptionState.REVOKED
            else -> LatestSubscriptionState.UNKNOWN
        }

    override fun getWillAutoRenew(purchase: Purchase): Boolean = purchase.isAutoRenewing

    override fun getOfferType(
        purchase: Purchase,
        product: StoreProduct?,
    ): LatestPeriodType? {
        if (product == null) return null

        val phasesWithoutTrial =
            when (val offer = product.rawStoreProduct?.selectedOffer) {
                is RawStoreProduct.SelectedOfferDetails.Subscription -> {
                    offer.underlying.pricingPhases.pricingPhaseList
                        .dropWhile { it.priceAmountMicros == 0L }
                }
                is RawStoreProduct.SelectedOfferDetails.OneTime -> emptyList() // One-time products don't have pricing phases
                null -> emptyList()
            }

        return when {
            // If we are in a trial period
            (product.trialPeriodEndDate?.time ?: 0) > System.currentTimeMillis() ->
                LatestPeriodType.TRIAL

            // Promo period with discounted price
            phasesWithoutTrial.size > 1 &&
                (phasesWithoutTrial.firstOrNull()?.priceAmountMicros ?: 0) <
                (phasesWithoutTrial.lastOrNull()?.priceAmountMicros ?: 0) ->
                LatestPeriodType.PROMOTIONAL

            // Unknown state - we assume it is revoked
            purchase.purchaseState == PurchaseState.UNSPECIFIED_STATE ->
                LatestPeriodType.REVOKED

            // Regular subscription
            else -> LatestPeriodType.SUBSCRIPTION
        }
    }

    override suspend fun isActive(
        purchase: Purchase,
        product: StoreProduct?,
    ): Boolean = purchase.purchaseState == PurchaseState.PURCHASED && product != null
}
