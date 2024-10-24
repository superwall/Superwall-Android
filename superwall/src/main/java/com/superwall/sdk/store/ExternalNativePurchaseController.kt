package com.superwall.sdk.store

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.superwall.sdk.Superwall
import com.superwall.sdk.billing.RECONNECT_TIMER_MAX_TIME_MILLISECONDS
import com.superwall.sdk.billing.RECONNECT_TIMER_START_MILLISECONDS
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.store.abstractions.product.OfferType
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

class ExternalNativePurchaseController(
    var context: Context,
    val scope: IOScope,
) : PurchaseController,
    PurchasesUpdatedListener {
    private var billingClient: BillingClient =
        BillingClient
            .newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()

    private val isConnected = MutableStateFlow(false)
    private val purchaseResults = MutableStateFlow<PurchaseResult?>(null)

    // how long before the data source tries to reconnect to Google play
    private var reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS

    //region Initialization

    init {
        scope.launch {
            startConnection()
        }
    }

    private fun startConnection() {
        try {
            billingClient.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        isConnected.value =
                            billingResult.responseCode == BillingClient.BillingResponseCode.OK
                        syncSubscriptionStatus()
                    }

                    override fun onBillingServiceDisconnected() {
                        isConnected.value = false

                        Logger.debug(
                            LogLevel.error,
                            LogScope.nativePurchaseController,
                            "ExternalNativePurchaseController billing client disconnected, " +
                                "retrying in $reconnectMilliseconds milliseconds",
                        )

                        CoroutineScope(Dispatchers.IO).launch {
                            delay(reconnectMilliseconds)
                            startConnection()
                        }

                        reconnectMilliseconds =
                            min(
                                reconnectMilliseconds * 2,
                                RECONNECT_TIMER_MAX_TIME_MILLISECONDS,
                            )
                    }
                },
            )
        } catch (e: IllegalStateException) {
            Logger.debug(
                LogLevel.error,
                LogScope.nativePurchaseController,
                "IllegalStateException when connecting to billing client for " +
                    "ExternalNativePurchaseController: ${e.message}",
            )
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

    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult {
        // Clear previous purchase results to avoid emitting old results
        purchaseResults.value = null

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
                basePlanId = basePlanId ?: "",
                offerType = offerId?.let { OfferType.Offer(id = it) },
            )

        val offerToken = rawStoreProduct.selectedOffer?.offerToken

        val isOneTime =
            productDetails.productType == BillingClient.ProductType.INAPP && offerToken.isNullOrEmpty()

        val productDetailsParams =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(productDetails)
                .also {
                    // Do not set empty offer token for one time products
                    // as Google play is not supporting it since June 12th 2024
                    if (!isOneTime) {
                        it.setOfferToken(offerToken ?: "")
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
                    if (shouldPassIdToPlayStore && id != null) {
                        setObfuscatedAccountId(id)
                    }
                }.setProductDetailsParamsList(listOf(productDetailsParams))
                .build()

        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.nativePurchaseController,
            message = "Waiting for billing client to be connected",
        )

        // Wait until the billing client becomes connected
        isConnected.first { it }

        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.nativePurchaseController,
            message = "Billing client is connected",
        )

        billingClient.launchBillingFlow(activity, flowParams)

        // Wait until a purchase result is emitted before returning the result
        val value = purchaseResults.first { it != null } ?: PurchaseResult.Failed("Purchase failed")

        return value
    }

    override suspend fun restorePurchases(): RestorationResult {
        syncSubscriptionStatusAndWait()
        return RestorationResult.Restored()
    }

//endregion

//region PurchasesUpdatedListener

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        // Determine the result based on the billing response code
        val result =
            when (billingResult.responseCode) {
                // If the purchase was successful, acknowledge any necessary purchases and create a Purchased result
                BillingClient.BillingResponseCode.OK -> {
                    purchases?.let { acknowledgePurchasesIfNecessary(it) }
                    PurchaseResult.Purchased()
                }

                // If the user canceled the purchase, create a Cancelled result
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    PurchaseResult.Cancelled()
                }

                // For all other response codes, create a Failed result with an exception
                else -> {
                    PurchaseResult.Failed(billingResult.responseCode.toString())
                }
            }

        scope.launch {
            // Emit the purchase result to any observers
            purchaseResults.emit(result)

            // Sync the subscription status with the server
            syncSubscriptionStatus()
        }
    }

//endregion

//region Private

    private suspend fun syncSubscriptionStatusAndWait() {
        val subscriptionPurchases = queryPurchasesOfType(BillingClient.ProductType.SUBS)
        val inAppPurchases = queryPurchasesOfType(BillingClient.ProductType.INAPP)
        val allPurchases = subscriptionPurchases + inAppPurchases

        val hasActivePurchaseOrSubscription =
            allPurchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val status: SubscriptionStatus =
            if (hasActivePurchaseOrSubscription) SubscriptionStatus.ACTIVE else SubscriptionStatus.INACTIVE

        if (!Superwall.initialized) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.nativePurchaseController,
                message = "Attempting to sync subscription status before Superwall has been initialized.",
            )
            return
        }

        Superwall.instance.setSubscriptionStatus(status)
    }

    private suspend fun queryPurchasesOfType(productType: String): List<Purchase> {
        val deferred = CompletableDeferred<List<Purchase>>()

        val params =
            QueryPurchasesParams
                .newBuilder()
                .setProductType(productType)
                .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.nativePurchaseController,
                    message = "Unable to query for purchases.",
                )
                return@queryPurchasesAsync
            }

            deferred.complete(purchasesList)
        }

        return deferred.await()
    }

    private fun acknowledgePurchasesIfNecessary(purchases: List<Purchase>) {
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.isAcknowledged == false }
            .forEach { purchase ->
                val acknowledgePurchaseParams =
                    AcknowledgePurchaseParams
                        .newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.nativePurchaseController,
                            message = "Unable to acknowledge purchase.",
                        )
                    }
                }
            }
    }

//endregion
}
