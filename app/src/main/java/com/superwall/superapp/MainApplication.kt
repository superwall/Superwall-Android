package com.superwall.superapp

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.identity.identify
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.store.coordinator.TransactionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class PurchaseControllerImpl(var context: Context) : PurchaseController, PurchasesUpdatedListener {

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()



    // Create a supervisor job
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    private val purchaseResults = MutableStateFlow<PurchaseResult?>(null)

    init {
        scope.launch {
            startConnection()
        }
    }

    private suspend fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _isConnected.value = true
                } else {
                    // TODO: Handle error
                    Log.d("!!!BillingController", "Billing client failed to connect")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d("!!!BillingController", "Billing client service  disconnected...")
                scope.launch {
                    startConnection()
                }
            }
        })
    }
    override suspend fun purchase(activity: Activity, product: com.android.billingclient.api.SkuDetails): PurchaseResult {
        // Wait for

        val skuDetails = product
        val flowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(skuDetails)
            .build()

        // Wait for connection using kotlinx.coroutines.flow.StateFlow
        println("!! Waiting for connection ${Thread.currentThread().name}")
        _isConnected.first { it }
        println("!! Connected ${Thread.currentThread().name}")


        billingClient.launchBillingFlow(activity, flowParams)

        println("!! (from app) purchase: $product")

        // Get the result from the flow
        return purchaseResults.first { it != null }!!
    }

    override suspend fun restorePurchases(): RestorationResult {
        println("!! (from app) restorePurchases")
        return RestorationResult.Failed(Exception("Restore failed"))
    }

    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
        // TODO: Handle this
        println("!! (from app) onPurchasesUpdated: $p0, $p1")

//        when (p0.responseCode) {
//            is BillingClient.BillingResponseCode -> {
//                if (p1 != null) {
//                    for (purchase in p1) {
//                        println("!! (from app) purchase: $purchase")
//                        purchaseResults.value = PurchaseResult.Purchased()
//                    }
//                }
//            }
//        }
        if (p0.responseCode == BillingClient.BillingResponseCode.OK) {
            if (p1 != null) {
                for (purchase in p1) {
                    println("!! (from app) purchase: $purchase")
                    purchaseResults.value = PurchaseResult.Purchased()
                }
            } else {
                print("!! (from app) purchase failed: ${p0.responseCode}")
            }
        } else {
            // TODO: Handle error
            // print
            print("!! (from app) purchase failed: ${p0.responseCode}")
            /*
                int SERVICE_TIMEOUT = -3;
                int FEATURE_NOT_SUPPORTED = -2;
                int SERVICE_DISCONNECTED = -1;
                int OK = 0;
                int USER_CANCELED = 1;
                int SERVICE_UNAVAILABLE = 2;
                int BILLING_UNAVAILABLE = 3;
                int ITEM_UNAVAILABLE = 4;
                int DEVELOPER_ERROR = 5;
                int ERROR = 6;
                int ITEM_ALREADY_OWNED = 7;
                int ITEM_NOT_OWNED = 8;
             */
            purchaseResults.value = PurchaseResult.Failed(Exception("Purchase failed"))
        }

    }
}

class MainApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()

//        // Superwall Android

        val purchaseController =  PurchaseControllerImpl(this)

        Superwall.configure(this, "pk_5f6d9ae96b889bc2c36ca0f2368de2c4c3d5f6119aacd3d2", purchaseController)
//
//        // TODO: Fix this so we don't need to make the user set this
        Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)
//        invokeRegister()

//        // UI-Tests
//        Superwall.configure(this, "pk_5f6d9ae96b889bc2c36ca0f2368de2c4c3d5f6119aacd3d2")
//
//        // TODO: Fix this so we don't need to make the user set this
//        Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)
//
//        // Test 0
//        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
//        Superwall.instance.register("present_data")
    }

    fun invokeRegister(
        event: String = "campaign_trigger",
        params: Map<String, Any>? = null
    ) {
        Superwall.instance.register(event, params)
    }
}