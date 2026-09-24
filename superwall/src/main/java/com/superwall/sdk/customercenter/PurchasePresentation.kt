package com.superwall.sdk.customercenter

import com.superwall.sdk.models.customer.NonSubscriptionTransaction
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.product.Store
import java.math.BigDecimal
import java.util.Date

/** Display-oriented product info, decoupled from `StoreProduct` for testability. */
internal data class ProductDisplayInfo(
    val productId: String,
    /**
     * The product's display name, or `null` when neither Google Play nor the catalogue supplied
     * one. Never the identifier standing in for one: a card without a name shows no name.
     */
    val title: String? = null,
    val localizedPrice: String? = null,
    val price: BigDecimal? = null,
    val localizedPeriod: String? = null,
    val isAutoRenewable: Boolean? = null,
) {
    companion object
}

internal enum class PurchaseBadge {
    LIFETIME,
    REVOKED,
    EXPIRED,
    BILLING_ISSUE,
    CANCELLED,
    FREE_TRIAL,
    ACTIVE,
}

internal sealed class PurchaseKind {
    data class Subscription(
        val transaction: SubscriptionTransaction,
    ) : PurchaseKind()

    data class NonSubscription(
        val transaction: NonSubscriptionTransaction,
    ) : PurchaseKind()

    data class EntitlementOnly(
        val entitlement: Entitlement,
    ) : PurchaseKind()
}

internal data class PurchasePresentation(
    val id: String,
    val kind: PurchaseKind,
    val productId: String?,
    /**
     * What the card is headed with: the product's display name, else the entitlement it unlocks,
     * else nothing. The purchase is always shown; only this label is allowed to be absent.
     */
    val title: String?,
    val priceLine: String?,
    val statusLine: String,
    val badge: PurchaseBadge,
    val store: Store,
    val storeLabelKey: String?,
    val isActive: Boolean,
    val expirationDate: Date?,
    val purchaseDate: Date?,
    /** The entitlements the purchase unlocks. */
    val entitlements: Set<Entitlement> = emptySet(),
    /** Whether the product's name and price are still loading from the Superwall catalogue. */
    val isAwaitingCatalogue: Boolean = false,
) {
    /** What the delegate is told about this purchase. */
    val publicPurchase: CustomerCenterPurchase
        get() =
            when (kind) {
                is PurchaseKind.Subscription ->
                    CustomerCenterPurchase(productId, store, entitlements, subscription = kind.transaction)
                is PurchaseKind.NonSubscription ->
                    CustomerCenterPurchase(productId, store, entitlements, nonSubscription = kind.transaction)
                is PurchaseKind.EntitlementOnly ->
                    CustomerCenterPurchase(productId, store, entitlements)
            }

    val subscription: SubscriptionTransaction?
        get() = (kind as? PurchaseKind.Subscription)?.transaction

    /**
     * Whether the row opens a detail screen — which is where a purchase's own actions live.
     *
     * A subscription does. So does an entitlement-only purchase: a web subscription arrives as a
     * bare entitlement whenever the backend sends no matching transaction, and that customer still
     * has a management page to reach. A one-off purchase has no action of its own, so it stays a
     * plain card.
     */
    val opensDetail: Boolean
        get() = kind !is PurchaseKind.NonSubscription
}

/** Stores the SDK can drive on Android: Google Play natively, and the web stores via their management page. */
internal val drivableStores = setOf(Store.PLAY_STORE, Store.STRIPE, Store.PADDLE, Store.SUPERWALL)

internal val webStores = setOf(Store.STRIPE, Store.PADDLE, Store.SUPERWALL)
