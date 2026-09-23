package com.superwall.sdk.customercenter

import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.customer.NonSubscriptionTransaction
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.store.abstractions.product.receipt.LatestPeriodType
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Builds display-ready [PurchasePresentation] rows from raw [CustomerInfo]. */
internal class PurchasePresentationBuilder(
    private val strings: CustomerCenterStrings,
    locale: Locale = Locale.getDefault(),
    // Dates follow the same locale as the strings, not whatever the JVM default happens to be.
    private val dateFormatter: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale),
) {
    /**
     * @param awaitingCatalogue Products whose details are still loading, whose cards show
     *   placeholders for them meanwhile.
     */
    fun build(
        customerInfo: CustomerInfo,
        products: Map<String, ProductDisplayInfo>,
        awaitingCatalogue: Set<String> = emptySet(),
    ): List<PurchasePresentation> {
        val entitlementsByProductId = customerInfo.entitlementsByProductId()
        val subs = subscriptionPresentations(customerInfo.subscriptions, products, entitlementsByProductId)
        val nonSubs = nonSubscriptionPresentations(customerInfo.nonSubscriptions, products, entitlementsByProductId)
        val knownProductIds =
            (customerInfo.subscriptions.map { it.productId } + customerInfo.nonSubscriptions.map { it.productId }).toSet()
        val entitlementOnly =
            customerInfo.entitlements
                .filter { it.isActive && it.productIds.none(knownProductIds::contains) }
                .map(::entitlementPresentation)
        return (subs + nonSubs + entitlementOnly).map { purchase ->
            purchase.copy(isAwaitingCatalogue = purchase.productId?.let(awaitingCatalogue::contains) ?: false)
        }
    }

    fun subscriptionPresentations(
        subscriptions: List<SubscriptionTransaction>,
        products: Map<String, ProductDisplayInfo>,
        entitlementsByProductId: Map<String, Set<Entitlement>> = emptyMap(),
    ): List<PurchasePresentation> {
        // `CustomerInfo.subscriptions` can carry one entry per transaction, which includes past
        // renewals of a subscription. Collapse to one row per product: prefer the active
        // transaction, otherwise the one with the latest expiration date (null dates last).
        val latestPerProduct = LinkedHashMap<String, SubscriptionTransaction>()
        for (sub in subscriptions) {
            val existing = latestPerProduct[sub.productId]
            if (existing == null || isPreferred(sub, existing)) {
                latestPerProduct[sub.productId] = sub
            }
        }
        val sorted =
            latestPerProduct.values.sortedWith { lhs, rhs ->
                when {
                    lhs.isActive != rhs.isActive -> if (lhs.isActive) -1 else 1
                    lhs.expirationDate != null && rhs.expirationDate != null ->
                        lhs.expirationDate.compareTo(rhs.expirationDate)
                    lhs.expirationDate == null && rhs.expirationDate != null -> 1
                    lhs.expirationDate != null && rhs.expirationDate == null -> -1
                    else -> lhs.purchaseDate.compareTo(rhs.purchaseDate)
                }
            }
        return sorted.map {
            presentation(it, products[it.productId], entitlementsByProductId[it.productId] ?: emptySet())
        }
    }

    /**
     * Whether [lhs] better represents its product than [rhs] when both are transactions of the
     * same subscription: active wins, then the latest expiration date (null dates last), then the
     * latest purchase date.
     */
    private fun isPreferred(
        lhs: SubscriptionTransaction,
        rhs: SubscriptionTransaction,
    ): Boolean {
        if (lhs.isActive != rhs.isActive) return lhs.isActive
        val lhsDate = lhs.expirationDate
        val rhsDate = rhs.expirationDate
        return when {
            lhsDate != null && rhsDate != null -> lhsDate > rhsDate
            lhsDate != null -> true
            rhsDate != null -> false
            else -> lhs.purchaseDate > rhs.purchaseDate
        }
    }

    fun nonSubscriptionPresentations(
        purchases: List<NonSubscriptionTransaction>,
        products: Map<String, ProductDisplayInfo>,
        entitlementsByProductId: Map<String, Set<Entitlement>> = emptyMap(),
    ): List<PurchasePresentation> =
        purchases.sortedBy { it.purchaseDate }.map { purchase ->
            val product = products[purchase.productId]
            val entitlements = entitlementsByProductId[purchase.productId] ?: emptySet()
            // Keyed by transaction id, not product id: consumables can legitimately be purchased
            // multiple times, and each purchase gets its own row.
            PurchasePresentation(
                id = purchase.transactionId,
                kind = PurchaseKind.NonSubscription(purchase),
                productId = purchase.productId,
                title = product?.title ?: entitlementTitle(entitlements),
                priceLine = product?.localizedPrice,
                statusLine =
                    if (purchase.isRevoked) {
                        strings.string("customer_center_revoked")
                    } else {
                        strings.string("customer_center_purchased_on", format(purchase.purchaseDate))
                    },
                badge = if (purchase.isRevoked) PurchaseBadge.REVOKED else PurchaseBadge.ACTIVE,
                store = purchase.store,
                storeLabelKey = storeLabelKey(purchase.store),
                isActive = !purchase.isRevoked,
                expirationDate = null,
                purchaseDate = purchase.purchaseDate,
                entitlements = entitlements,
            )
        }

    private fun presentation(
        sub: SubscriptionTransaction,
        product: ProductDisplayInfo?,
        entitlements: Set<Entitlement>,
    ): PurchasePresentation {
        val badge = badge(sub)
        val price = product?.localizedPrice
        val date = sub.expirationDate?.let(::format)
        val status =
            when (badge) {
                PurchaseBadge.REVOKED -> strings.string("customer_center_revoked")
                PurchaseBadge.EXPIRED ->
                    date?.let { strings.string("customer_center_expired_on", it) }
                        ?: strings.string("customer_center_expired")
                PurchaseBadge.BILLING_ISSUE -> strings.string("customer_center_billing_issue")
                PurchaseBadge.CANCELLED -> date?.let { strings.string("customer_center_expires_on", it) } ?: ""
                PurchaseBadge.FREE_TRIAL -> date?.let { strings.string("customer_center_free_trial_until", it) } ?: ""
                PurchaseBadge.LIFETIME -> strings.string("customer_center_lifetime")
                PurchaseBadge.ACTIVE ->
                    when {
                        date != null && price != null -> strings.string("customer_center_renews_on_for", date, price)
                        date != null -> strings.string("customer_center_renews_on", date)
                        else -> ""
                    }
            }
        val priceLine =
            price?.let {
                product.localizedPeriod?.let { period ->
                    strings.string("customer_center_price_per_period", it, period)
                } ?: it
            }
        return PurchasePresentation(
            id = sub.productId,
            kind = PurchaseKind.Subscription(sub),
            productId = sub.productId,
            title = product?.title ?: entitlementTitle(entitlements),
            priceLine = priceLine,
            statusLine = status,
            badge = badge,
            store = sub.store,
            storeLabelKey = storeLabelKey(sub.store),
            isActive = sub.isActive,
            expirationDate = sub.expirationDate,
            purchaseDate = sub.purchaseDate,
            entitlements = entitlements,
        )
    }

    private fun entitlementPresentation(entitlement: Entitlement): PurchasePresentation {
        val isLifetime = entitlement.isLifetime == true
        val date = entitlement.expiresAt?.let(::format)
        val status =
            when {
                isLifetime -> strings.string("customer_center_lifetime")
                date != null -> strings.string("customer_center_expires_on", date)
                else -> strings.string("customer_center_active_via_superwall")
            }
        val store = entitlement.store ?: Store.SUPERWALL
        return PurchasePresentation(
            id = "entitlement:${entitlement.id}",
            kind = PurchaseKind.EntitlementOnly(entitlement),
            productId = entitlement.latestProductId,
            title = entitlement.id,
            priceLine = null,
            statusLine = status,
            badge = if (isLifetime) PurchaseBadge.LIFETIME else PurchaseBadge.ACTIVE,
            store = store,
            storeLabelKey = storeLabelKey(store),
            isActive = entitlement.isActive,
            expirationDate = entitlement.expiresAt,
            purchaseDate = entitlement.startsAt,
            entitlements = setOf(entitlement),
        )
    }

    fun badge(sub: SubscriptionTransaction): PurchaseBadge =
        when {
            sub.isRevoked -> PurchaseBadge.REVOKED
            !sub.isActive -> PurchaseBadge.EXPIRED
            sub.isInGracePeriod || sub.isInBillingRetryPeriod -> PurchaseBadge.BILLING_ISSUE
            !sub.willRenew -> PurchaseBadge.CANCELLED
            sub.offerType == LatestPeriodType.TRIAL -> PurchaseBadge.FREE_TRIAL
            else -> PurchaseBadge.ACTIVE
        }

    fun storeLabelKey(store: Store): String? =
        when (store) {
            Store.PLAY_STORE -> null
            Store.STRIPE, Store.PADDLE -> "customer_center_store_web"
            Store.APP_STORE -> "customer_center_store_app_store"
            Store.SUPERWALL -> "customer_center_store_superwall"
            Store.OTHER, Store.CUSTOM -> "customer_center_store_other"
        }

    private fun format(date: Date): String = dateFormatter.format(date)

    companion object {
        /**
         * The title a purchase falls back to when its product has no display name: the
         * entitlement it unlocks. With no product name and no entitlement either, the card has
         * no title — never the raw product identifier, which for a Stripe price reads
         * `test:price_1Tu…:no-trial`.
         *
         * A product can grant several entitlements; the lowest identifier is taken so the choice
         * is stable from one render to the next.
         */
        fun entitlementTitle(entitlements: Set<Entitlement>): String? = entitlements.map { it.id }.minOrNull()
    }
}

/** Every entitlement each product unlocks, as the customer's entitlements describe it. */
internal fun CustomerInfo.entitlementsByProductId(): Map<String, Set<Entitlement>> {
    val result = mutableMapOf<String, MutableSet<Entitlement>>()
    for (entitlement in entitlements) {
        val productIds = entitlement.productIds + listOfNotNull(entitlement.latestProductId)
        for (productId in productIds) {
            result.getOrPut(productId) { mutableSetOf() }.add(entitlement)
        }
    }
    return result
}
