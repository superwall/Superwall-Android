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
        get() =
            when (product?.kind) {
                StoreProductKind.Subscription ->
                    product.rawStoreProduct?.subscriptionPeriod?.let { Date(purchase.purchaseTime + it.toMillis) }
                else -> null
            }

    override val isRevoked: Boolean
        get() = purchase.state != StorePurchaseState.Purchased

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

    override val isInGracePeriod: Boolean
        get() = purchase.state == StorePurchaseState.Pending && purchase.autoRenewing

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
