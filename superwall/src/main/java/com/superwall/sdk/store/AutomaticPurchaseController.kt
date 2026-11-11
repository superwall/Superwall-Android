package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.Superwall
import com.superwall.sdk.billing.PurchaseOptions
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.config.models.ConfigurationStatus
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
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.abstractions.product.BasePlanType
import com.superwall.sdk.store.abstractions.product.OfferType
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.PlayBillingErrors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import com.superwall.sdk.billing.Store as BillingStore

private const val BILLING_UNAVAILABLE_ERROR = "Google Play Billing is not available on this device."

/**
 * Reads the entitlements of the last device CustomerInfo built from Play receipts, with Play's
 * hold dates standing in for the expiry Play doesn't give (see [deviceRecordsForEmptyRead]).
 * Empty when there is none or the read fails.
 */
internal fun readDeviceEntitlementRecords(storage: Storage): Set<Entitlement> =
    try {
        deviceRecordsForEmptyRead(storage)
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

/**
 * The [PurchaseController] used when the app doesn't provide one.
 *
 * It doesn't own a billing client: purchases, queries and acknowledgements go through [store],
 * the SDK's single Play Billing connection, so the SDK sees every purchase this controller makes.
 */
class AutomaticPurchaseController internal constructor(
    val scope: IOScope,
    private val store: BillingStore,
    val entitlementsInfo: () -> Entitlements,
    // The status holds config-shaped entitlements, which carry no expiry date.
    // The device CustomerInfo built from Play receipts does, and it survives
    // launches, so it is what an empty read gets measured against.
    val deviceEntitlementRecords: () -> Set<Entitlement>,
    private val passIdentifiersToPlayStore: () -> Boolean,
    private val userId: () -> String,
    private val externalAccountId: () -> String,
    private val configurationState: () -> Flow<ConfigurationStatus>,
    private val setSubscriptionStatus: (SubscriptionStatus) -> Unit,
    // Emits each time the app comes to the foreground.
    private val appForegrounded: Flow<Unit> = emptyFlow(),
) : PurchaseController {
    companion object {
        private const val MAX_RETRIES = 3
    }

    // Purchase tokens with an acknowledgement in flight
    private val acknowledgingTokens = ConcurrentHashMap.newKeySet<String>()

    //region Initialization

    private val initialSync: Job

    init {
        initialSync = syncSubscriptionStatus()
        // A subscription can expire, or be refunded or revoked, while the app is in the
        // background. Play doesn't report that as a purchase update, so ask again.
        scope.launch {
            appForegrounded.collect {
                // The launch sync already covers a foreground that arrives while it runs.
                if (!initialSync.isActive) {
                    syncSubscriptionStatus()
                }
            }
        }
        // Every purchase Play reports - from our flows, pending purchases settling, promo
        // codes - can change what the user is entitled to.
        scope.launch {
            store.externalPurchases.collect { purchase ->
                acknowledgePurchasesIfNecessary(listOf(purchase))
                syncSubscriptionStatus()
            }
        }
    }

    //endregion

    //region Public

    private fun syncSubscriptionStatus(): Job =
        scope.launch {
            Superwall.hasInitialized.first { it }
            syncSubscriptionStatusAndWait()
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
                // offerId is the offer already chosen for this purchase; null means the base plan.
                // OfferType.from(null) is Auto, which would pick the longest trial instead.
                offerType = offerId?.let { OfferType.from(it) } ?: OfferType.None,
            )

        val shouldPassIdToPlayStore =
            try {
                passIdentifiersToPlayStore()
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
                userId()
            } catch (e: Throwable) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.nativePurchaseController,
                    message = "Error getting userId",
                )
                null
            }

        // Waits for Play's answer to this flow; fails straight away if billing can't connect.
        val outcome =
            store.purchase(
                activity = activity,
                product = StoreProduct(rawStoreProduct),
                options =
                    PurchaseOptions(
                        obfuscatedAccountId = externalAccountId(),
                        replacement = replacement,
                    ),
            )
        return outcome.toPurchaseResult()
    }


    override suspend fun restorePurchases(): RestorationResult {
        syncSubscriptionStatusAndWait()
        return RestorationResult.Restored()
    }

//endregion


//region Private

    private suspend fun syncSubscriptionStatusAndWait(count: Int = 0) {
        // A failed read isn't "no purchases": keep what the store could read, and mark the read
        // as failed so the status logic doesn't take it as an answer.
        val active = store.activePurchases()
        val failed = active.isFailure
        val allPurchases = active.getOrElse { store.ownedPurchases() }

        // Purchases that completed outside the billing flow callback (pending ones that
        // settled, process death mid-flow, Play Store promo codes, failed acknowledgements)
        // only surface here. Play refunds them after 3 days if left unacknowledged.
        acknowledgePurchasesIfNecessary(allPurchases)
        val hasActivePurchaseOrSubscription =
            allPurchases.any { it.state == StorePurchaseState.Purchased }
        val activeProductIds =
            allPurchases
                .filter { it.state == StorePurchaseState.Purchased }
                .flatMap { it.productIds }
                .toSet()

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.nativePurchaseController,
            message = "Found purchases: ${
                allPurchases.mapIndexed { i, it ->
                    val items = it.productIds.joinToString(",")
                    "<$i. Products: $items id: ${it.orderId} time: ${it.purchaseTime} state: ${it.state} >"
                }
            }",
        )

        val status: SubscriptionStatus =
            if (hasActivePurchaseOrSubscription) {
                // We await for configuration to be set so our entitlements are available
                configurationState().first { it is ConfigurationStatus.Configured }
                allPurchases
                    .flatMap {
                        it.productIds
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
                configurationState().first { it !is ConfigurationStatus.Pending }
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

        setSubscriptionStatus(status)

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

    private fun StorePurchaseResult.toPurchaseResult(): PurchaseResult =
        when (this) {
            is StorePurchaseResult.Purchased -> PurchaseResult.Purchased(purchases)
            StorePurchaseResult.Cancelled -> PurchaseResult.Cancelled()
            is StorePurchaseResult.Failed -> {
                // A flow Play answered carries its response code as the message; any other
                // failure means billing couldn't be reached.
                when (val code = error.message?.toIntOrNull()) {
                    null, BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                        PurchaseResult.Failed(BILLING_UNAVAILABLE_ERROR)
                    else ->
                        PurchaseResult.Failed(
                            PlayBillingErrors.fromCode(code)?.message ?: "Unknown error $code",
                        )
                }
            }
        }

    private fun acknowledgePurchasesIfNecessary(purchases: List<StorePurchase>) {
        purchases
            .filter { it.state == StorePurchaseState.Purchased && !it.acknowledged }
            // The flow result and the sync that follows it can see the same purchase
            .filter { acknowledgingTokens.add(it.token) }
            .forEach { purchase ->
                scope.launch {
                    try {
                        val acknowledged =
                            retryOrNull(MAX_RETRIES) { store.acknowledge(purchase).getOrThrow() } != null
                        if (!acknowledged) {
                            Logger.debug(
                                logLevel = LogLevel.error,
                                scope = LogScope.nativePurchaseController,
                                message = "Unable to acknowledge purchase, will retry on next sync.",
                                info = mapOf("order_id" to (purchase.orderId ?: "")),
                            )
                        }
                    } finally {
                        acknowledgingTokens.remove(purchase.token)
                    }
                }
            }
    }

//endregion
}
