package com.superwall.sdk.store.products

import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class Result<T> {
    data class Success<T>(val value: T): Result<T>()
    data class Error<T>(val error: Throwable): Result<T>()
    data class Waiting<T>(val startedAt: Int): Result<T>()
}


open class ProductFetcher(var context: Context) : PurchasesUpdatedListener {
    private lateinit var billingClient: BillingClient
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    // Create a map with product id to status
    private val _results = MutableStateFlow<Map<String, Result<RawStoreProduct>>>(emptyMap())
    val results: StateFlow<Map<String, Result<RawStoreProduct>>> = _results

    // Create a supervisor job
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()


        scope.launch {
            startConnection()
        }
    }


    private suspend fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d("!!!BillingController", "Billing client setup finished".plus(billingResult.responseCode))
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // The billing client is ready. You can query purchases here.
                    Log.d("!!!BillingController", "Billing client connected")
                    _isConnected.value = true
                } else {
                    Log.d("!!!BillingController", "Billing client failed to connect")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d("!!!BillingController", "Billing client service  disconnected...")
                // Try to restart the connection if it was lost.

                scope.launch {
                    startConnection()
                }
            }
        })
    }


    public fun request(productIds: List<String>)  {
        scope.launch {

        val currentResults = _results.value

        println("!!! Current results ${currentResults.size}")

        var productIdsToLoad: List<String> = emptyList()
        productIds.forEach { productId ->
            val result = currentResults[productId]
            println("Result for $productId is $result ${Thread.currentThread().name}")
            if (result == null) {
                productIdsToLoad = productIdsToLoad + productId
            }
        }

        print("!!! Requesting ${productIdsToLoad.size} products")

        if (!productIdsToLoad.isEmpty()) {
                _results.emit(
                    _results.value + productIdsToLoad.map { it to Result.Waiting(startedAt = System.currentTimeMillis().toInt()) }
                )

//            scope.launch {
                val networkResult = queryProductDetails(productIdsToLoad)
                _results.emit(
                    _results.value + networkResult.mapValues { it.value  }
                )
//            }
//
        }

        }
    }

    suspend fun requestAndAwait(productIds: List<String>): Map<String, Result<RawStoreProduct>>  {
        request(productIds)
        results.map { currentResults ->
            productIds.all { productId ->
                currentResults[productId] is Result.Success || currentResults[productId] is Result.Error
            }
        }.first { it == true }
        return _results.value.filterKeys { it in productIds }
    }


    open suspend fun queryProductDetails(productIds: List<String>): Map<String, Result<RawStoreProduct>> {

        val deferred = CompletableDeferred<Map<String, Result<RawStoreProduct>>>(emptyMap())

        val skuList = ArrayList<String>()
        skuList.addAll(productIds)
        val params = SkuDetailsParams.newBuilder()
        params.setSkusList(skuList).setType(BillingClient.SkuType.SUBS)

        billingClient.querySkuDetailsAsync(params.build()) {
                billingResult, skuDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
                deferred.complete(skuDetailsList.associateBy { it.sku }.mapValues { Result.Success(RawStoreProduct(it.value)) })
            } else {

                // Fail all of them
                val failed: Map<String, Result<RawStoreProduct>> = productIds.map { it  to Result.Error<RawStoreProduct>(Exception("Failed to query product details")) }.toMap()

                deferred.complete(failed)
            }
        }

        return deferred.await()
    }

    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
        TODO("Not yet implemented")
    }

//
//    suspend fun products(
//        identifiers: Set<String>,
//        forPaywall: String?
//    ): Set<StoreProduct>  {
//
//
//
//        // Make sure it's connected before we do anything
//        Log.d("!!!BillingController", "Waiting for connection...")
//        isConnected.filter { it }.first()
//        Log.d("!!!BillingController", "Connected!")
//
//        return suspendCancellableCoroutine { cancellableContinuation ->
//
//
//
//            val params = SkuDetailsParams.newBuilder()
//                .setSkusList(identifiers.toList())
//                .setType(BillingClient.SkuType.SUBS)
//                .build()
//
//            billingClient.querySkuDetailsAsync(params) { billingResult, skuDetailsList ->
//                // Process the result.
//                Log.d("!!!BillingController", "Got sku details: $skuDetailsList")
//                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
//
//                    cancellableContinuation.resume()
//                } else {
//                    cancellableContinuation.resumeWithException(Exception("Failed to get sku details"))
//                }
//            }
//
//        }
//    }
//
//    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
//        TODO("Not yet implemented")
//    }
}