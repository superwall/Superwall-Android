package com.superwall.sdk.customercenter

import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.customer.NonSubscriptionTransaction
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.store.abstractions.product.receipt.LatestPeriodType
import java.util.Date

internal object CustomerCenterFixtures {
    const val DAY_MS = 24 * 60 * 60 * 1000L
    val now = Date(1_800_000_000_000L)

    fun subscription(
        productId: String = "monthly",
        store: Store = Store.PLAY_STORE,
        isActive: Boolean = true,
        willRenew: Boolean = true,
        isRevoked: Boolean = false,
        isInGracePeriod: Boolean = false,
        isInBillingRetryPeriod: Boolean = false,
        offerType: LatestPeriodType? = null,
        purchaseDate: Date = Date(now.time - DAY_MS),
        expirationDate: Date? = Date(now.time + 29 * DAY_MS),
        transactionId: String = "tx-$productId",
    ) = SubscriptionTransaction(
        transactionId = transactionId,
        productId = productId,
        purchaseDate = purchaseDate,
        willRenew = willRenew,
        isRevoked = isRevoked,
        isInGracePeriod = isInGracePeriod,
        isInBillingRetryPeriod = isInBillingRetryPeriod,
        isActive = isActive,
        expirationDate = expirationDate,
        store = store,
        offerType = offerType,
    )

    fun nonSubscription(
        productId: String = "coins",
        store: Store = Store.PLAY_STORE,
        isRevoked: Boolean = false,
        transactionId: String = "tx-$productId",
        purchaseDate: Date = Date(now.time - DAY_MS),
    ) = NonSubscriptionTransaction(
        transactionId = transactionId,
        productId = productId,
        purchaseDate = purchaseDate,
        isConsumable = false,
        isRevoked = isRevoked,
        store = store,
    )

    fun entitlement(
        id: String = "pro",
        isActive: Boolean = true,
        productIds: Set<String> = emptySet(),
        store: Store? = null,
        isLifetime: Boolean? = null,
        expiresAt: Date? = null,
        latestProductId: String? = null,
    ) = Entitlement(
        id = id,
        isActive = isActive,
        productIds = productIds,
        store = store,
        isLifetime = isLifetime,
        expiresAt = expiresAt,
        latestProductId = latestProductId,
    )

    fun customerInfo(
        subscriptions: List<SubscriptionTransaction> = emptyList(),
        nonSubscriptions: List<NonSubscriptionTransaction> = emptyList(),
        entitlements: List<Entitlement> = emptyList(),
    ) = CustomerInfo(
        subscriptions = subscriptions,
        nonSubscriptions = nonSubscriptions,
        userId = "user",
        entitlements = entitlements,
    )

    fun presentation(
        info: CustomerInfo,
        products: Map<String, ProductDisplayInfo> = emptyMap(),
    ): PurchasePresentation = PurchasePresentationBuilder(CustomerCenterStrings.english).build(info, products).first()
}
