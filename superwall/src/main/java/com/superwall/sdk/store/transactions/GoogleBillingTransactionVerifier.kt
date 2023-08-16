package com.superwall.sdk.store.transactions

import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.coordinator.TransactionChecker
import com.superwall.sdk.store.products.GooglePlayProductsFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GoogleBillingTransactionVerifier(var context: Context): TransactionChecker,
    PurchasesUpdatedListener {
    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    // Create a supervisor job
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    override suspend fun getAndValidateLatestTransaction(
        productId: String,
        hasPurchaseController: Boolean
    ): StoreTransaction? {
        throw java.lang.Error("Should never need this with a purchase controller")
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        println("onPurchasesUpdated: $result")
    }
}