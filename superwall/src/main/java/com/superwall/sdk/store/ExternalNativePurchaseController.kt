package com.superwall.sdk.store

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ExternalNativePurchaseController(var context: Context) : PurchaseController, PurchasesUpdatedListener {
    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()
    private val isConnected = MutableStateFlow(false)
    private val purchaseResults = MutableStateFlow<PurchaseResult?>(null)

    //region Initialization

    init {
        CoroutineScope(Dispatchers.IO).launch {
            startConnection()
        }
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnected.value = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                syncSubscriptionStatus()
            }

            override fun onBillingServiceDisconnected() {
                isConnected.value = false

                CoroutineScope(Dispatchers.IO).launch {
                    startConnection()
                }
            }
        })
    }

    //endregion

    //region Public

    fun syncSubscriptionStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            syncSubscriptionStatusAndWait()
        }
    }

    //endregion

    //region PurchaseController

    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?
    ): PurchaseResult {
        val offerToken = productDetails.subscriptionOfferDetails
            ?.firstOrNull { it.offerId == offerId }
            ?.offerToken
            ?: ""

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        
        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.nativePurchaseController,
            message = "Waiting for billing client to be connected"
        )

        // Wait until the billing client becomes connected
        isConnected.first { it }

        Logger.debug(
            logLevel = LogLevel.info,
            scope = LogScope.nativePurchaseController,
            message = "Billing client is connected"
        )

        billingClient.launchBillingFlow(activity, flowParams)

        // Wait until a purchase result is emitted before returning the result
        val value =  purchaseResults.first { it != null } ?: PurchaseResult.Failed("Purchase failed")

        return value
    }

    override suspend fun restorePurchases(): RestorationResult {
        syncSubscriptionStatusAndWait()
        return RestorationResult.Restored()
    }

    //endregion

    //region PurchasesUpdatedListener

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        // Determine the result based on the billing response code
        val result = when (billingResult.responseCode) {
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

        CoroutineScope(Dispatchers.IO).launch {
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

        val hasActivePurchaseOrSubscription = allPurchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val status: SubscriptionStatus = if (hasActivePurchaseOrSubscription) SubscriptionStatus.ACTIVE else SubscriptionStatus.INACTIVE

        if (Superwall.initialized == false) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.nativePurchaseController,
                message = "Attempting to sync subscription status before Superwall has been initialized."
            )
            return
        }

        Superwall.instance.setSubscriptionStatus(status)
    }

    private suspend fun queryPurchasesOfType(productType: String): List<Purchase> {
        val deferred = CompletableDeferred<List<Purchase>>()

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(productType)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.nativePurchaseController,
                    message = "Unable to query for purchases."
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
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.nativePurchaseController,
                            message = "Unable to acknowledge purchase."
                        )
                    }
                }
            }
    }

    //endregion
}