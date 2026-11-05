package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.superwall.sdk.billing.StoreProductKind
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.billing.google.kind
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
    override suspend fun subscriptionState(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): LatestSubscriptionState {
        val timeSincePurchase = System.currentTimeMillis() - purchase.purchaseTime
        val duration = product?.rawStoreProduct?.subscriptionPeriod?.toMillis ?: 0

        return when {
            // Revoked/refunded
            purchase.state == StorePurchaseState.Unknown -> LatestSubscriptionState.REVOKED

            // Grace period - pending but still auto-renewing
            purchase.state == StorePurchaseState.Pending && purchase.autoRenewing ->
                LatestSubscriptionState.GRACE_PERIOD

            // Expired - purchased but past duration
            purchase.state == StorePurchaseState.Purchased && duration > 0 && timeSincePurchase > duration ->
                LatestSubscriptionState.EXPIRED

            // Active subscription
            purchase.state == StorePurchaseState.Purchased &&
                purchase.autoRenewing &&
                (duration == 0L || timeSincePurchase < duration) ->
                LatestSubscriptionState.SUBSCRIBED

            // Expired (non-renewing or past duration)
            purchase.state == StorePurchaseState.Purchased -> LatestSubscriptionState.EXPIRED

            else -> LatestSubscriptionState.UNKNOWN
        }
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

    override suspend fun isActive(
        purchase: StorePurchase,
        product: StoreProduct?,
    ): Boolean {
        if (purchase.state != StorePurchaseState.Purchased || product == null) return false
        return when (product.kind) {
            // Non-consumable is always active if purchased
            StoreProductKind.OneTime -> true
            StoreProductKind.Subscription -> {
                // If we can't determine expiration, assume active
                val period = product.rawStoreProduct?.subscriptionPeriod ?: return true
                purchase.purchaseTime + period.toMillis > System.currentTimeMillis()
            }
            StoreProductKind.Unknown -> false
        }
    }
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
