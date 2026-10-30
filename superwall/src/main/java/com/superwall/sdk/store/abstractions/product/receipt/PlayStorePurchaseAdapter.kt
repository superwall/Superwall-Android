package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.store.abstractions.product.StoreProduct
import java.util.Date

/**
 * Adapts a Google Play Purchase to the EntitlementTransaction interface.
 *
 * Play Billing only tells the client that a purchase exists and whether it's paid for. It has
 * no client-side signal for expiry, grace period or account hold (only the Play Developer API
 * and RTDN see those), so we report what we know and leave the rest unknown:
 * - a purchase returned by `queryPurchases` in the PURCHASED state is active now;
 * - PENDING means payment hasn't completed yet (not revoked, not active);
 * - [expirationDate] is unknown (null): renewals keep the original purchase token and
 *   `purchaseTime`, and trials/intro phases change the first period's length, so
 *   `purchaseTime + period` is wrong for anything past the first plain period.
 */
class PlayStorePurchaseAdapter(
    private val purchase: Purchase,
    private val product: StoreProduct?,
    override val productId: String,
) : EntitlementTransaction {
    override val transactionId: String
        get() = purchase.orderId ?: purchase.purchaseToken

    override val purchaseDate: Date
        get() = Date(purchase.purchaseTime)

    override val originalPurchaseDate: Date
        get() = Date(purchase.purchaseTime) // Google Play doesn't distinguish original vs renewal

    override val expirationDate: Date?
        get() = null

    override val isRevoked: Boolean
        get() = purchase.purchaseState == PurchaseState.UNSPECIFIED_STATE

    override val isPending: Boolean
        get() = purchase.purchaseState == PurchaseState.PENDING

    override val productType: EntitlementTransactionType
        get() =
            when (product?.rawStoreProduct?.underlyingProductDetails?.productType) {
                ProductType.INAPP -> EntitlementTransactionType.NON_CONSUMABLE
                ProductType.SUBS ->
                    if (purchase.isAutoRenewing) {
                        EntitlementTransactionType.AUTO_RENEWABLE
                    } else {
                        EntitlementTransactionType.NON_RENEWABLE
                    }
                else -> EntitlementTransactionType.CONSUMABLE
            }

    override val willRenew: Boolean
        get() = purchase.isAutoRenewing

    override val renewedAt: Date?
        get() = null // Google Play doesn't provide this directly

    // During a real Play grace period, queryPurchases still reports PURCHASED. Grace and
    // account hold are only visible server-side, so the client never claims either.
    override val isInGracePeriod: Boolean
        get() = false

    override val isInBillingRetryPeriod: Boolean
        get() = false

    override val isActive: Boolean
        get() = calculateIsActive()

    private fun calculateIsActive(): Boolean =
        when (purchase.purchaseState) {
            PurchaseState.PURCHASED -> true
            PurchaseState.PENDING -> false
            PurchaseState.UNSPECIFIED_STATE -> false
            else -> false
        }

    companion object {
        /**
         * Creates adapters for all products in a purchase.
         *
         * @param purchase The Google Play purchase
         * @param productsById Map of products keyed by raw product ID (productIdentifier)
         * @return List of adapters, one for each product in the purchase
         */
        fun fromPurchase(
            purchase: Purchase,
            productsById: Map<String, StoreProduct>,
        ): List<PlayStorePurchaseAdapter> =
            purchase.products.map { productId ->
                val product = productsById[productId]
                PlayStorePurchaseAdapter(
                    purchase = purchase,
                    product = product,
                    // Use fullIdentifier for entitlement matching when product is found,
                    // otherwise fall back to raw productId
                    productId = product?.fullIdentifier ?: productId,
                )
            }
    }
}
