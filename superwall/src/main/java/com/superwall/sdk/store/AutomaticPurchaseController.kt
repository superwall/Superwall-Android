package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.Superwall
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.PurchaseFlowResult
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.retryOrNull
import com.superwall.sdk.models.customer.toSet
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.storage.LatestDeviceCustomerInfo
import com.superwall.sdk.store.abstractions.product.BasePlanType
import com.superwall.sdk.store.abstractions.product.OfferType
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.PlayBillingErrors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val BILLING_UNAVAILABLE_ERROR = "Google Play Billing is not available on this device."

/**
 * The [PurchaseController] used when the app doesn't provide one.
 *
 * It doesn't own a billing client: purchases, queries and acknowledgements go through
 * [billing], the SDK's single Play Billing connection.
 */
class AutomaticPurchaseController(
    val scope: IOScope,
    private val billing: Billing,
    val entitlementsInfo: () -> Entitlements = { Superwall.instance.dependencyContainer.entitlements },
    // The status holds config-shaped entitlements, which carry no expiry date.
    // The device CustomerInfo built from Play receipts does, and it survives
    // launches, so it is what an empty read gets measured against.
    val deviceEntitlementRecords: () -> Set<Entitlement> = {
        try {
            Superwall.instance.dependencyContainer.storage
                .read(LatestDeviceCustomerInfo)
                ?.entitlements
                ?.toSet() ?: emptySet()
        } catch (e: Throwable) {
            // Without the dates we can only fall back to demoting, which is
            // what this read would have done anyway.
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.nativePurchaseController,
                message = "Unable to read the stored device entitlements.",
                error = e,
            )
            emptySet()
        }
    },
) : PurchaseController {
    companion object {
        private const val MAX_RETRIES = 3
    }

    // Purchase tokens with an acknowledgement in flight
    private val acknowledgingTokens = ConcurrentHashMap.newKeySet<String>()

    //region Initialization

    init {
        syncSubscriptionStatus()
        // Every purchase update Play reports - from our flows, pending purchases settling,
        // promo codes - can change what the user is entitled to.
        scope.launch {
            billing.purchaseResults
                .filterNotNull()
                .collect { result ->
                    (result as? InternalPurchaseResult.Purchased)?.let {
                        acknowledgePurchasesIfNecessary(listOf(it.purchase))
                    }
                    syncSubscriptionStatus()
                }
        }
    }

    //endregion

    //region Public

    private fun syncSubscriptionStatus() {
        scope.launch {
            Superwall.hasInitialized.first { it }
            syncSubscriptionStatusAndWait()
        }
    }

    //endregion

    //region PurchaseController

    private fun buildFullId(
        subscriptionId: String,
        basePlanId: String?,
        offerId: String?,
    ): String =
        buildString {
            append(subscriptionId)
            basePlanId?.let { append(":$it") }
            offerId?.let { append(":$it") }
        }

    // The billing flow lives on the deprecated ProductDetails signature; the
    // PurchaseController default bridges purchase(activity, product, ...) to it.
    @Deprecated(
        "Implement purchase(activity, product, basePlanId, offerId) instead. " +
            "It receives a StoreProduct, which also supports custom store products.",
        ReplaceWith("purchase(activity, product, basePlanId, offerId)"),
    )
    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult = launchPurchase(activity, productDetails, basePlanId, offerId, replacement = null)

    override suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        basePlanId: String?,
        offerId: String?,
        replacement: ProductReplacement?,
    ): PurchaseResult {
        // Custom products can't go through Play, so let the default fail them
        val rawStoreProduct =
            product.rawStoreProduct
                ?: return purchase(activity, product, basePlanId, offerId)
        return launchPurchase(activity, rawStoreProduct.underlyingProductDetails, basePlanId, offerId, replacement)
    }

    private suspend fun launchPurchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
        replacement: ProductReplacement?,
    ): PurchaseResult {
        val fullId =
            buildFullId(
                subscriptionId = productDetails.productId,
                basePlanId = basePlanId,
                offerId = offerId,
            )

        val rawStoreProduct =
            RawStoreProduct(
                underlyingProductDetails = productDetails,
                fullIdentifier = fullId,
                basePlanType = BasePlanType.from(basePlanId),
                offerType = OfferType.from(offerId),
            )

        val offerToken =
            when (val offer = rawStoreProduct.selectedOffer) {
                is RawStoreProduct.SelectedOfferDetails.Subscription -> offer.underlying.offerToken
                is RawStoreProduct.SelectedOfferDetails.OneTime -> {
                    // For OTP with purchase options, we need the offerToken to specify which
                    // purchase option to use, even when there's no discount offer (offerId=null).
                    // Only skip offerToken for legacy OTPs without purchase options.
                    if (offer.purchaseOptionId != null || offerId != null) {
                        offer.underlying.offerToken
                    } else {
                        null
                    }
                }

                null -> null
            }

        val hasOfferToken = !offerToken.isNullOrEmpty()

        val productDetailsParams =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(productDetails)
                .also {
                    // Set offer token if we have one (for both subscriptions and OTP with purchase options)
                    // Don't set empty token as Google Play doesn't support it
                    if (hasOfferToken) {
                        it.setOfferToken(offerToken!!)
                    }
                }.build()

        val shouldPassIdToPlayStore =
            try {
                Superwall.instance.options.passIdentifiersToPlayStore
            } catch (e: Throwable) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.nativePurchaseController,
                    message = "Error getting Superwall options",
                )
                false
            }

        val id =
            try {
                Superwall.instance.userId
            } catch (e: Throwable) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.nativePurchaseController,
                    message = "Error getting userId",
                )
                null
            }
        val flowParams =
            BillingFlowParams
                .newBuilder()
                .apply {
                    setObfuscatedAccountId(Superwall.instance.externalAccountId)
                }.setProductDetailsParamsList(listOf(productDetailsParams))
                .apply {
                    replacement?.toSubscriptionUpdateParams()?.let { setSubscriptionUpdateParams(it) }
                }.build()

        // Waits for Play's answer to this flow; fails straight away if billing can't connect.
        val outcome = billing.launchBillingFlow(activity, flowParams)
        return outcome.toPurchaseResult()
    }

    override suspend fun restorePurchases(): RestorationResult {
        syncSubscriptionStatusAndWait()
        return RestorationResult.Restored()
    }

//endregion

//region Private

    private fun PurchaseFlowResult.toPurchaseResult(): PurchaseResult =
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> PurchaseResult.Purchased(purchases)
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled()
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> PurchaseResult.Failed(BILLING_UNAVAILABLE_ERROR)
            else ->
                PurchaseResult.Failed(
                    PlayBillingErrors.fromCode(billingResult.responseCode)?.message
                        ?: "Unknown error ${billingResult.responseCode}",
                )
        }

    private fun ProductReplacement.toSubscriptionUpdateParams(): BillingFlowParams.SubscriptionUpdateParams? {
        val oldPurchaseToken = oldPurchaseToken ?: return null
        return BillingFlowParams.SubscriptionUpdateParams
            .newBuilder()
            .setOldPurchaseToken(oldPurchaseToken)
            .apply {
                // Without a mode, Play uses the one configured for the base plan in Play Console
                when (mode) {
                    ProductReplacement.Mode.DEFAULT -> Unit
                    ProductReplacement.Mode.CHARGE_PRORATED ->
                        setSubscriptionReplacementMode(
                            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE,
                        )
                    ProductReplacement.Mode.CHARGE_FULL_PRICE ->
                        setSubscriptionReplacementMode(
                            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE,
                        )
                }
            }.build()
    }

    private suspend fun syncSubscriptionStatusAndWait(count: Int = 0) {
        val (subscriptionPurchases, inAppPurchases) =
            coroutineScope {
                val subs =
                    async {
                        retryOrNull(MAX_RETRIES) { billing.queryPurchasesOfType(BillingClient.ProductType.SUBS).getOrThrow() }
                    }
                val inApps =
                    async {
                        retryOrNull(MAX_RETRIES) { billing.queryPurchasesOfType(BillingClient.ProductType.INAPP).getOrThrow() }
                    }
                subs.await() to inApps.await()
            }
        val failed = subscriptionPurchases == null || inAppPurchases == null
        val allPurchases = (subscriptionPurchases ?: emptyList()) + (inAppPurchases ?: emptyList())

        // Purchases that completed outside the billing flow callback (pending ones that
        // settled, process death mid-flow, Play Store promo codes, failed acknowledgements)
        // only surface here. Play refunds them after 3 days if left unacknowledged.
        acknowledgePurchasesIfNecessary(allPurchases)
        val hasActivePurchaseOrSubscription =
            allPurchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val activeProductIds =
            allPurchases
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .flatMap { it.products }
                .toSet()

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.nativePurchaseController,
            message = "Found purchases: ${
                allPurchases.mapIndexed { i, it ->
                    val items = it.products.joinToString(",")
                    "<$i. Products: $items id: ${it.orderId} time: ${it.purchaseTime} state: ${it.purchaseState} >"
                }
            }",
        )

        val status: SubscriptionStatus =
            if (hasActivePurchaseOrSubscription) {
                // We await for configuration to be set so our entitlements are available
                Superwall.instance.configurationStateListener.first { it is ConfigurationStatus.Configured }
                allPurchases
                    .flatMap {
                        it.products
                    }.toSet()
                    .flatMap {
                        val res = entitlementsInfo().byProductId(it)
                        res
                    }.toSet()
                    .let { entitlements ->
                        Logger.debug(
                            logLevel = LogLevel.debug,
                            scope = LogScope.nativePurchaseController,
                            message = "Found entitlements: ${entitlements.joinToString { it.id }}",
                        )

                        if (entitlements.isNotEmpty()) {
                            entitlementsInfo().activeDeviceEntitlements = entitlements
                            SubscriptionStatus.Active(
                                entitlements.map { it.copy(isActive = true) }.toSet(),
                            )
                        } else {
                            // An active purchase that maps to no entitlement is a
                            // mapping failure, not an answer about what it unlocks.
                            statusForEmptyRead(
                                activeProductIds = activeProductIds,
                                readReturnedPurchases = allPurchases.isNotEmpty(),
                                readFailed = failed,
                            )
                        }
                    }
            } else {
                // Wait until config has finished applying (or failed) so flags like
                // test mode are known before publishing Inactive - otherwise we'd
                // fire a spurious status change in test-mode apps
                Superwall.instance.configurationStateListener.first { it !is ConfigurationStatus.Pending }
                statusForEmptyRead(
                    activeProductIds = activeProductIds,
                    readReturnedPurchases = allPurchases.isNotEmpty(),
                    readFailed = failed,
                )
            }
        if (!Superwall.initialized) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.nativePurchaseController,
                message = "Attempting to sync subscription status before Superwall has been initialized.",
            )
            return
        }

        Superwall.instance.internallySetSubscriptionStatus(status)

        if (failed && count < MAX_RETRIES) {
            scope.launch {
                delay(count.seconds)
                syncSubscriptionStatusAndWait(count + 1)
            }
        }
    }

    /**
     * Resolves the status for a read that produced no entitlements, keeping a
     * subscriber whose entitlement hasn't expired active when the read was not
     * an answer. See [resolveStatusForEmptyRead].
     */
    private fun statusForEmptyRead(
        activeProductIds: Set<String>,
        readReturnedPurchases: Boolean,
        readFailed: Boolean,
    ): SubscriptionStatus {
        val deviceRecords = deviceEntitlementRecords()
        val resolved =
            resolveStatusForEmptyRead(
                currentStatus = entitlementsInfo().status.value,
                deviceRecords = deviceRecords,
                activeProductIds = activeProductIds,
                readReturnedPurchases = readReturnedPurchases,
                readFailed = readFailed,
            )

        // Keep the device view in step with the status we are about to publish,
        // or `Entitlements.active` would go on serving whatever we just dropped.
        // Only Play entitlements belong here: web ones live in `Entitlements.web`,
        // and `WebPaywallRedeemer.clear()` relies on this set holding none.
        // A config-shaped entitlement with no store and no device record is
        // still a device one unless the web set claims it.
        val storeById = deviceRecords.associate { it.id to it.store }
        val webIds = entitlementsInfo().web.map { it.id }.toSet()
        entitlementsInfo().activeDeviceEntitlements =
            if (resolved is SubscriptionStatus.Active) {
                resolved.entitlements
                    .filter {
                        when (it.store ?: storeById[it.id]) {
                            Store.PLAY_STORE -> true
                            null -> it.id !in webIds
                            else -> false
                        }
                    }.toSet()
            } else {
                emptySet()
            }

        if (resolved is SubscriptionStatus.Active) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.nativePurchaseController,
                message =
                    "Read returned no entitlements and was not an answer, keeping: " +
                        resolved.entitlements.joinToString { it.id },
            )
        }

        return resolved
    }

    private fun acknowledgePurchasesIfNecessary(purchases: List<Purchase>) {
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            // The flow callback and the sync that follows it can see the same purchase
            .filter { acknowledgingTokens.add(it.purchaseToken) }
            .forEach { purchase ->
                scope.launch {
                    try {
                        val acknowledged =
                            retryOrNull(MAX_RETRIES) { billing.acknowledge(purchase.purchaseToken).getOrThrow() } != null
                        if (!acknowledged) {
                            Logger.debug(
                                logLevel = LogLevel.error,
                                scope = LogScope.nativePurchaseController,
                                message = "Unable to acknowledge purchase, will retry on next sync.",
                                info = mapOf("order_id" to (purchase.orderId ?: "")),
                            )
                        }
                    } finally {
                        acknowledgingTokens.remove(purchase.purchaseToken)
                    }
                }
            }
    }

//endregion
}
