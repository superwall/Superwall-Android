package com.superwall.sdk.store.abstractions.product.receipt

import com.superwall.sdk.billing.StoreProductKind
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.billing.google.kind
import com.superwall.sdk.store.abstractions.product.StoreProduct
import java.util.Date

/**
 * A store purchase of one product as an [EntitlementTransaction], read only from the neutral
 * [StorePurchase]. [productId] is the product's full identifier when the product is known.
 *
 * Play Billing only tells the client that a purchase exists and whether it's paid for. It has
 * no client-side signal for expiry, grace period or account hold (only the Play Developer API
 * and RTDN see those), so this reports what is known and leaves the rest unknown:
 * - a purchase the store returns in the purchased state is active now;
 * - a pending one is waiting on payment: neither revoked nor active;
 * - [expirationDate] is unknown (null): renewals keep the original purchase token and
 *   `purchaseTime`, and trials or intro phases change the first period's length, so
 *   `purchaseTime + period` is wrong for anything past the first plain period.
 */
internal class StorePurchaseTransaction(
    private val purchase: StorePurchase,
    private val product: StoreProduct?,
    override val productId: String,
) : EntitlementTransaction {
    override val transactionId: String
        get() = purchase.orderId ?: purchase.token

    override val purchaseDate: Date
        get() = Date(purchase.purchaseTime)

    // The store doesn't tell an original purchase from a renewal
    override val originalPurchaseDate: Date
        get() = Date(purchase.purchaseTime)

    override val expirationDate: Date?
        get() = null

    override val isRevoked: Boolean
        get() = purchase.state == StorePurchaseState.Unknown

    override val isPending: Boolean
        get() = purchase.state == StorePurchaseState.Pending

    override val productType: EntitlementTransactionType
        get() =
            when (product?.kind) {
                StoreProductKind.OneTime -> EntitlementTransactionType.NON_CONSUMABLE
                StoreProductKind.Subscription ->
                    if (purchase.autoRenewing) {
                        EntitlementTransactionType.AUTO_RENEWABLE
                    } else {
                        EntitlementTransactionType.NON_RENEWABLE
                    }
                else -> EntitlementTransactionType.CONSUMABLE
            }

    override val willRenew: Boolean
        get() = purchase.autoRenewing

    override val renewedAt: Date?
        get() = null

    // During a real Play grace period the store still reports the purchase as purchased. Grace
    // and account hold are only visible server-side, so the client never claims either.
    override val isInGracePeriod: Boolean
        get() = false

    override val isInBillingRetryPeriod: Boolean
        get() = false

    override val isActive: Boolean
        get() = purchase.state == StorePurchaseState.Purchased

    companion object {
        /**
         * One transaction per product in [purchase]. [productsById] is keyed by raw product id;
         * a known product is matched by its full identifier, an unknown one by its raw id.
         */
        fun fromPurchase(
            purchase: StorePurchase,
            productsById: Map<String, StoreProduct>,
        ): List<StorePurchaseTransaction> =
            purchase.productIds.map { productId ->
                val product = productsById[productId]
                StorePurchaseTransaction(
                    purchase = purchase,
                    product = product,
                    productId = product?.fullIdentifier ?: productId,
                )
            }
    }
}
