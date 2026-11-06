package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.billing.google.offerPhasePricesMicros
import com.superwall.sdk.store.abstractions.product.StoreProduct

/** Reads subscription status from a neutral [StorePurchase]. */
internal interface StoreSubscriptionStatus {
    suspend fun subscriptionState(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestSubscriptionState

    fun willAutoRenew(purchase: StorePurchase): Boolean

    fun offerType(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestPeriodType?

    suspend fun isActive(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): Boolean
}

/** The SDK's own status logic, on neutral types only. */
internal object DefaultSubscriptionStatus : StoreSubscriptionStatus {
    /**
     * A purchase the store returns in the purchased state is entitled now, so it is SUBSCRIBED.
     * Play Billing has no client-side expiry, grace or hold signal, so none is derived from
     * `purchaseTime`, which stays at the original purchase across renewals. When there's no
     * current purchase at all, see [ReceiptManager.lastSubscriptionProperties].
     */
    override suspend fun subscriptionState(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestSubscriptionState =
        when (purchase.state) {
            StorePurchaseState.Purchased -> LatestSubscriptionState.SUBSCRIBED
            StorePurchaseState.Unknown -> LatestSubscriptionState.REVOKED
            StorePurchaseState.Pending -> LatestSubscriptionState.UNKNOWN
        }

    override fun willAutoRenew(purchase: StorePurchase): Boolean = purchase.autoRenewing

    override fun offerType(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestPeriodType? {
        if (product == null) return null
        val phasesWithoutTrial = product.offerPhasePricesMicros.dropWhile { it == 0L }

        return when {
            // If we are in a trial period
            (product.trialPeriodEndDate?.time ?: 0) > System.currentTimeMillis() -> LatestPeriodType.TRIAL

            // Promo period with discounted price
            phasesWithoutTrial.size > 1 &&
                (phasesWithoutTrial.firstOrNull() ?: 0) < (phasesWithoutTrial.lastOrNull() ?: 0) ->
                LatestPeriodType.PROMOTIONAL

            // Unknown state - we assume it is revoked
            purchase.state == StorePurchaseState.Unknown -> LatestPeriodType.REVOKED

            // Regular subscription
            else -> LatestPeriodType.SUBSCRIPTION
        }
    }

    // A purchased product the store still returns is active: the client can't see an expiry.
    override suspend fun isActive(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): Boolean = purchase.state == StorePurchaseState.Purchased && product != null
}

/**
 * A custom public [SubscriptionStatusProvider], which takes Play purchases. Each neutral
 * purchase is handed over as the Play [Purchase] it came from; one never seen as a Play
 * purchase falls back to [DefaultSubscriptionStatus].
 */
internal class ProviderSubscriptionStatus(
    private val provider: SubscriptionStatusProvider,
    private val playPurchase: (StorePurchase) -> Purchase?,
) : StoreSubscriptionStatus {
    override suspend fun subscriptionState(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestSubscriptionState =
        playPurchase(purchase)?.let { provider.getSubscriptionState(it, product) }
            ?: DefaultSubscriptionStatus.subscriptionState(purchase, product)

    override fun willAutoRenew(purchase: StorePurchase): Boolean =
        playPurchase(purchase)?.let(provider::getWillAutoRenew)
            ?: DefaultSubscriptionStatus.willAutoRenew(purchase)

    override fun offerType(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestPeriodType? {
        // The offer type is nullable, so a provider's `null` is its answer: only a purchase
        // never seen as a Play purchase falls back to the SDK's own logic.
        val play = playPurchase(purchase) ?: return DefaultSubscriptionStatus.offerType(purchase, product)
        return provider.getOfferType(play, product)
    }

    override suspend fun isActive(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): Boolean =
        playPurchase(purchase)?.let { provider.isActive(it, product) }
            ?: DefaultSubscriptionStatus.isActive(purchase, product)

    companion object {
        /** The SDK's own logic for its default provider, else [provider] behind this adapter. */
        fun of(
            provider: SubscriptionStatusProvider,
            playPurchase: (StorePurchase) -> Purchase?,
        ): StoreSubscriptionStatus =
            if (provider is PlayBillingSubscriptionStatusProvider) {
                DefaultSubscriptionStatus
            } else {
                ProviderSubscriptionStatus(provider, playPurchase)
            }
    }
}
