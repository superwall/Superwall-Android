package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.customer.NonSubscriptionTransaction
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductsFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*

data class InAppPurchase(
    var productIdentifier: String,
)

data class TransactionReceipt(
    val originalTransactionId: String,
    val purchaseToken: String,
)

enum class LatestSubscriptionOfferType {
    TRIAL,
    CODE,
    PROMOTIONAL,
    WINBACK,
}

class ReceiptManager(
    private var delegate: ProductsFetcher?,
    private val billing: Billing,
    private val ioScope: IOScope = IOScope(),
//    private val receiptData: () -> ByteArray? = ReceiptLogic::getReceiptData
) {
    var latestSubscriptionStateValue: LatestSubscriptionState? = null
    var latestSubscriptionWillAutoRenew: Boolean? = null
    var latestSubscriptionPeriodType: LatestPeriodType? = null
    var transactionReceipts: MutableList<TransactionReceipt> = mutableListOf()

    data class PurchaseSnapshot(
        val purchases: Set<InAppPurchase>,
        val entitlementsByProductId: Map<String, Set<Entitlement>>,
        val nonSubscriptions: List<NonSubscriptionTransaction>,
        val subscriptions: List<SubscriptionTransaction>,
    )

    var purchasedSubscriptionGroupIds: Set<String>? = null
    private var _purchases: MutableSet<InAppPurchase> = mutableSetOf()
    private var receiptRefreshCompletion: ((Boolean) -> Unit)? = null
    private val latestSubscriptionState: MutableStateFlow<LatestSubscriptionState> =
        MutableStateFlow(LatestSubscriptionState.UNKNOWN)

    val purchases: Set<String>
        get() = _purchases.map { it.productIdentifier }.toSet()

    @Suppress("RedundantSuspendModifier")
    suspend fun loadPurchasedProducts(): Set<StoreProduct>? =
        billing
            .queryAllPurchases()
            .flatMap { it.products }
            .let { products ->
                _purchases.addAll(products.map { InAppPurchase(it) }.toSet())
                delegate?.products(products.toSet())
            }?.toSet()

    suspend fun loadPurchases(serverEntitlementsByProductId: Map<String, Set<Entitlement>>): PurchaseSnapshot {
        var purchases: MutableSet<InAppPurchase> = mutableSetOf()
        var originalTransactionIds: MutableSet<String> = mutableSetOf()
        transactionReceipts.clear()

        // Note: Need to access Superwall instance through proper dependency injection
        val enableExperimentalDeviceVariables = false // TODO: Get from proper source

        // per-entitlement groupings
        val entitlementsByProductId: MutableMap<String, MutableSet<Entitlement>> = mutableMapOf()
        val productIdsByEntitlementId: MutableMap<String, MutableSet<String>> = mutableMapOf()
        val purchasesPerEntitlement: MutableMap<String, MutableList<Purchase>> = mutableMapOf()
        val nonSubscriptions: MutableList<NonSubscriptionTransaction> = mutableListOf()
        val subscriptions: MutableList<SubscriptionTransaction> = mutableListOf()

        // Initialize entitlement mappings
        serverEntitlementsByProductId.forEach {
            val serverEntitlements = it.value
            val productId = it.key
            serverEntitlements.forEach { entitlement ->
                productIdsByEntitlementId.getOrPut(entitlement.id) { mutableSetOf() }.add(productId)
                val allProductIds = productIdsByEntitlementId[entitlement.id] ?: setOf(productId)
                entitlementsByProductId.getOrPut(productId) { mutableSetOf() }.add(
                    Entitlement(
                        id = entitlement.id,
                        type = entitlement.type,
                        productIds = allProductIds,
                    ),
                )
            }
        }

        // 1️⃣ FIRST PASS: collect purchases & receipts & build transaction data
        val allPurchases = billing.queryAllPurchases()
        allPurchases.forEach { purchase ->
            val isActive = isTransactionActive(purchase)
            val products = billing.awaitGetProducts(purchase.products.toSet())
            purchase.products.forEach { productId ->
                val product = products.find { it.fullIdentifier == productId }
                if (product?.rawStoreProduct?.underlyingProductDetails?.productType == BillingClient.ProductType.INAPP) {
                    nonSubscriptions.add(
                        NonSubscriptionTransaction(
                            transactionId = purchase.purchaseToken.hashCode().toULong(),
                            productId = productId,
                            purchaseDate = Date(purchase.purchaseTime),
                            isConsumable = false,
                            isRevoked = false,
                        ),
                    )
                } else {
                    subscriptions.add(
                        SubscriptionTransaction(
                            transactionId = purchase.purchaseToken.hashCode().toULong(),
                            productId = productId,
                            purchaseDate = Date(purchase.purchaseTime),
                            willRenew = purchase.isAutoRenewing,
                            isRevoked = purchase.purchaseState == PurchaseState.PENDING,
                            isInGracePeriod = false, // Will be updated later
                            isInBillingRetryPeriod = false, // Will be updated later
                            isActive = isActive,
                            expirationDate = calculateExpirationDate(purchase, product),
                        ),
                    )
                }
                val serverEntitlements = serverEntitlementsByProductId[productId]
                if (serverEntitlements != null) {
                    // Map transactions and their product IDs to each entitlement
                    for (entitlement in serverEntitlements) {
                        purchasesPerEntitlement
                            .getOrPut(entitlement.id) { mutableListOf() }
                            .add(purchase)
                    }
                }
                purchases.add(
                    InAppPurchase(productIdentifier = productId),
                )
            }

            // Collect receipt for original transaction
            if (!originalTransactionIds.contains(purchase.orderId ?: purchase.purchaseToken)) {
                transactionReceipts.add(
                    TransactionReceipt(
                        originalTransactionId = purchase.orderId ?: purchase.purchaseToken,
                        purchaseToken = purchase.purchaseToken,
                    ),
                )
                originalTransactionIds.add(purchase.orderId ?: purchase.purchaseToken)
            }
        }

        // 2️⃣ SECOND PASS: build entitlements with comprehensive state analysis
        purchasesPerEntitlement.forEach { (entitlementId, purchaseList) ->
            val now = Date()
            var isActive = false
            var renewedAt: Date? = null
            var expiresAt: Date? = null
            var mostRecentRenewable: Purchase? = null
            var latestProductId: String? = null

            // Can't be done in the next loop
            val startsAt =
                purchaseList.minByOrNull { it.purchaseTime }?.let { Date(it.purchaseTime) }

            var isLifetime = false
            val lifetimePurchase =
                purchaseList.find { purchase ->
                    val products =
                        runBlocking { billing.awaitGetProducts(purchase.products.toSet()) }
                    val hasNonConsumable = products.any { it.productType == ProductType.INAPP }
                    hasNonConsumable && purchase.purchaseState != PurchaseState.PENDING
                }

            if (lifetimePurchase != null) {
                isLifetime = true
                latestProductId = lifetimePurchase.products.firstOrNull()
                isActive = true
            }

            // Single scan of this entitlement's purchases
            purchaseList.forEach { purchase ->
                // Any non-revoked, unexpired
                if (purchase.purchaseState != PurchaseState.PENDING) {
                    val products =
                        runBlocking { billing.awaitGetProducts(purchase.products.toSet()) }
                    val hasActiveSubscription =
                        products.any { product ->
                            val expDate = calculateExpirationDate(purchase, product)
                            expDate == null || expDate.after(now)
                        }
                    if (hasActiveSubscription) {
                        isActive = true
                    }
                }

                if (!isLifetime &&
                    (
                        mostRecentRenewable == null || mostRecentRenewable.purchaseTime < purchase.purchaseTime
                    )
                ) {
                    mostRecentRenewable = purchase
                }

                // Track renewal
                if (purchase.isAutoRenewing && purchase.purchaseState != PurchaseState.PENDING) {
                    if (renewedAt == null || renewedAt.before(Date(purchase.purchaseTime))) {
                        renewedAt = Date(purchase.purchaseTime)
                    }
                }

                // Latest expiration for non-lifetime
                if (!isLifetime && purchase.purchaseState != PurchaseState.PENDING) {
                    val products = runBlocking { billing.awaitGetProducts(purchase.products.toSet()) }
                    expiresAt =
                        products
                            .filter { it.productType == ProductType.SUBS }
                            .mapNotNull { calculateExpirationDate(purchase, it) }
                            .maxByOrNull { d1 ->
                                d1.time
                            }
                }
            }

            if (latestProductId == null) {
                latestProductId = mostRecentRenewable?.products?.firstOrNull()
            }

            val productIds = productIdsByEntitlementId[entitlementId] ?: emptySet()

            // Subscription status analysis
            var willRenew = false
            var state: LatestSubscriptionState? = null
            var offerType: LatestPeriodType? = null

            val subscriptionTxnIndex =
                subscriptions.indexOfFirst {
                    it.transactionId == mostRecentRenewable?.purchaseToken?.hashCode()?.toULong()
                }

            if (!isLifetime && mostRecentRenewable != null) {
                willRenew = mostRecentRenewable.isAutoRenewing

                if (subscriptionTxnIndex != -1) {
                    subscriptions[subscriptionTxnIndex] =
                        subscriptions[subscriptionTxnIndex].copy(willRenew = willRenew)
                }

                if (enableExperimentalDeviceVariables) {
                    latestSubscriptionWillAutoRenew = willRenew
                }

                state = getLatestSubscriptionState(mostRecentRenewable)
                if (subscriptionTxnIndex != -1) {
                    subscriptions[subscriptionTxnIndex] =
                        subscriptions[subscriptionTxnIndex].copy(
                            isInGracePeriod = state == LatestSubscriptionState.GRACE_PERIOD,
                            isInBillingRetryPeriod = state == LatestSubscriptionState.BILLING_RETRY,
                        )
                }

                if (enableExperimentalDeviceVariables) {
                    latestSubscriptionState.value = state
                }

                val products =
                    runBlocking { billing.awaitGetProducts(mostRecentRenewable.products.toSet()) }
                val product = products.firstOrNull()
                if (product != null) {
                    offerType = determineLatestPeriodType(mostRecentRenewable, product)
                    if (enableExperimentalDeviceVariables) {
                        latestSubscriptionPeriodType = offerType
                    }
                }
            }

            // Assemble and insert entitlements
            for (id in productIds) {
                val existingEntitlements =
                    entitlementsByProductId[id]?.toMutableSet() ?: mutableSetOf()
                var existingType: Entitlement.Type = Entitlement.Type.SERVICE_LEVEL

                // Remove existing entitlement with same ID, if any
                val existing = existingEntitlements.find { it.id == entitlementId }
                if (existing != null) {
                    existingType = existing.type
                    existingEntitlements.remove(existing)
                }

                // Insert updated entitlement
                existingEntitlements.add(
                    Entitlement(
                        id = entitlementId,
                        type = existingType,
                        isActive = isActive,
                        productIds = productIds,
                        latestProductId = latestProductId,
                        startsAt = startsAt,
                        renewedAt = renewedAt,
                        expiresAt = expiresAt,
                        isLifetime = isLifetime,
                        willRenew = willRenew,
                        state = state,
                        offerType = offerType,
                    ),
                )

                // Write back to the dictionary
                entitlementsByProductId[id] = existingEntitlements
            }
        }

        _purchases = purchases

        return PurchaseSnapshot(
            purchases = purchases,
            entitlementsByProductId = entitlementsByProductId,
            nonSubscriptions = nonSubscriptions.reversed(),
            subscriptions = subscriptions.reversed(),
        )
    }

    fun refreshReceipt() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message = "Refreshing receipts",
        )
        ioScope.launch {
            latestSubscriptionState.value = lastSubscriptionProperties()
        }
    }

    init {
        refreshReceipt()
    }

    suspend fun lastSubscriptionProperties(): LatestSubscriptionState =
        billing
            .queryAllPurchases()
            .maxByOrNull {
                it.purchaseTime
            }?.let {
                val purchase = it
                val timeSincePurchase = System.currentTimeMillis() - purchase.purchaseTime
                val willRenew = it.isAutoRenewing
                val productIds = purchase.products
                val product =
                    billing
                        .awaitGetProducts(productIds.toSet())
                        .firstOrNull()
                if (product == null) {
                    return@let emptyMap()
                }
                val duration = product.rawStoreProduct.subscriptionPeriod?.toMillis ?: 0
                val state =
                    when {
                        purchase.purchaseState == PurchaseState.PENDING &&
                            willRenew -> LatestSubscriptionState.GRACE_PERIOD

                        purchase.purchaseState == PurchaseState.PURCHASED &&
                            timeSincePurchase > duration -> LatestSubscriptionState.EXPIRED

                        purchase.purchaseState == PurchaseState.PURCHASED &&
                            willRenew &&
                            timeSincePurchase < duration -> LatestSubscriptionState.SUBSCRIBED

                        else -> {
                            LatestSubscriptionState.UNKNOWN
                        }
                    }
                state
            }

    fun determineLatestPeriodType(
        purchase: Purchase,
        productDetails: StoreProduct,
    ): LatestPeriodType {
        val phasesWithoutTrial =
            productDetails.rawStoreProduct.selectedOffer
                ?.pricingPhases
                ?.pricingPhaseList
                ?.dropWhile { it.priceAmountMicros == 0L } ?: emptyList()

        // Heuristics
        return when {
            // If we are in a trial period, it is a trial
            (productDetails.trialPeriodEndDate?.time ?: 0) > System.currentTimeMillis() -> LatestPeriodType.TRIAL

            // Promo period with discounted price - without trial, is there more than one phase where
            // the price is cheaper?
            phasesWithoutTrial.size > 1 &&
                (phasesWithoutTrial.firstOrNull()?.priceAmountMicros ?: 0) <
                (phasesWithoutTrial.lastOrNull()?.priceAmountMicros ?: 0) -> LatestPeriodType.PROMOTIONAL

            // Unknown state - we assume it is revoked
            purchase.purchaseState == Purchase.PurchaseState.UNSPECIFIED_STATE -> LatestPeriodType.REVOKED

            // If all else doesnt match, then the user is in a subscription
            else -> LatestPeriodType.SUBSCRIPTION
        }
    }

    fun hasPurchasedProduct(productId: String): Boolean = _purchases.firstOrNull { it.productIdentifier == productId } != null

    private fun isTransactionActive(purchase: Purchase): Boolean {
        val now = System.currentTimeMillis()

        when (purchase.purchaseState) {
            PurchaseState.PENDING -> return false
            PurchaseState.PURCHASED -> {
                // For subscriptions, check if not expired
                val products =
                    runBlocking {
                        try {
                            billing.awaitGetProducts(purchase.products.toSet())
                        } catch (e: Exception) {
                            Logger.debug(
                                logLevel = LogLevel.warn,
                                scope = LogScope.receipts,
                                message = "Failed to get product info for transaction check: ${e.message}",
                            )
                            return@runBlocking emptyList()
                        }
                    }

                for (product in products) {
                    val expiration = calculateExpirationDate(purchase, product)
                    if (expiration == null || expiration.time > now) {
                        return true
                    }
                }
                return false
            }

            PurchaseState.UNSPECIFIED_STATE -> return false
            else -> return false
        }
    }

    private fun calculateExpirationDate(
        purchase: Purchase,
        product: StoreProduct?,
    ): Date? {
        if (product == null) return null

        when (product.rawStoreProduct.underlyingProductDetails.productType) {
            ProductType.SUBS -> {
                val subscriptionPeriod = product.rawStoreProduct.subscriptionPeriod
                if (subscriptionPeriod != null) {
                    val periodMillis = subscriptionPeriod.toMillis
                    return Date(purchase.purchaseTime + periodMillis)
                }
            }

            ProductType.INAPP -> {
                // Non-consumable in-app purchases don't expire
                return null
            }
        }
        return null
    }

    private fun getLatestSubscriptionState(purchase: Purchase): LatestSubscriptionState {
        val now = System.currentTimeMillis()
        val timeSincePurchase = now - purchase.purchaseTime

        val products =
            runBlocking {
                try {
                    billing.awaitGetProducts(purchase.products.toSet())
                } catch (e: Exception) {
                    return@runBlocking emptyList()
                }
            }

        val product = products.firstOrNull()
        val duration = product?.rawStoreProduct?.subscriptionPeriod?.toMillis ?: 0

        return when {
            purchase.purchaseState == PurchaseState.PENDING &&
                purchase.isAutoRenewing -> LatestSubscriptionState.GRACE_PERIOD

            purchase.purchaseState == PurchaseState.PURCHASED &&
                timeSincePurchase > duration -> LatestSubscriptionState.EXPIRED

            purchase.purchaseState == PurchaseState.PURCHASED &&
                purchase.isAutoRenewing &&
                timeSincePurchase < duration -> LatestSubscriptionState.SUBSCRIBED

            else -> LatestSubscriptionState.EXPIRED
        }
    }

    suspend fun loadIntroOfferEligibility(storeProducts: Set<StoreProduct>) {
        // Android doesn't have a direct equivalent to iOS intro offer eligibility
        // This would need to be implemented based on purchase history analysis
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.receipts,
            message = "loadIntroOfferEligibility called for ${storeProducts.size} products",
        )
    }

    suspend fun isEligibleForIntroOffer(storeProduct: StoreProduct): Boolean {
        // Check if user has never purchased this subscription before
        val hasExistingPurchase =
            _purchases.any { it.productIdentifier == storeProduct.productIdentifier }
        return !hasExistingPurchase
    }
}
