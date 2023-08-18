package com.superwall.sdk.store.transactions

import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.store.abstractions.transactions.GoogleBillingPurchaseTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.coordinator.TransactionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

    // Setup mutable state flow for purchase results
    private val purchaseResults = MutableStateFlow<InternalPurchaseResult?>(null)

    override suspend fun getAndValidateLatestTransaction(
        productId: String,
        hasPurchaseController: Boolean
    ): StoreTransaction? {
       // Get the latest from purchaseResults
        purchaseResults.asStateFlow().filter { it != null }.first().let { purchaseResult ->
            return when (purchaseResult) {
                is InternalPurchaseResult.Purchased -> {
                    return purchaseResult.storeTransaction
                }
                is InternalPurchaseResult.Cancelled -> {
                    null
                }
                else -> {
                    null
                }
            }
        }

    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        println("onPurchasesUpdated: $result")
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                println("Purchase: $purchase")
                scope.launch {
                    purchaseResults.emit(InternalPurchaseResult.Purchased(storeTransaction = GoogleBillingPurchaseTransaction(purchase)))
                }
            }
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            scope.launch {
                purchaseResults.emit(InternalPurchaseResult.Cancelled)
            }

            println("User cancelled purchase")
        } else {
            scope.launch {
                purchaseResults.emit(InternalPurchaseResult.Failed(Exception(result.responseCode.toString())))
            }
            println("Purchase failed")
        }
    }
}