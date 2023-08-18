package com.superwall.sdk.store.products

import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductsFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class Result<T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Error<T>(val error: Throwable) : Result<T>()
    data class Waiting<T>(val startedAt: Int) : Result<T>()
}


open class GooglePlayProductsFetcher(var context: Context) : ProductsFetcher,
    PurchasesUpdatedListener {

    sealed class Result<T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Error<T>(val error: Throwable) : Result<T>()
        data class Waiting<T>(val startedAt: Int) : Result<T>()
    }


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
                Log.d(
                    "!!!BillingController",
                    "Billing client setup finished".plus(billingResult.responseCode)
                )
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


    protected fun request(productIds: List<String>) {
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
                    _results.value + productIdsToLoad.map {
                        it to Result.Waiting(
                            startedAt = System.currentTimeMillis().toInt()
                        )
                    }
                )

                println("!! Querying product details for ${productIdsToLoad.size} products, prodcuts: ${productIdsToLoad} ${Thread.currentThread().name}")
                val networkResult = runBlocking {
                    queryProductDetails(productIdsToLoad)
                }
                println("!! networkResult: ${networkResult} ${Thread.currentThread().name}")
                _results.emit(
                    _results.value + networkResult.mapValues { it.value }
                )
            }

        }
    }

    suspend fun _products(productIds: List<String>): Map<String, Result<RawStoreProduct>> {
        request(productIds)
        results.map { currentResults ->
            println("!! currentResults: ${currentResults} ${Thread.currentThread().name}")
            productIds.all { productId ->
                currentResults[productId] is Result.Success || currentResults[productId] is Result.Error
            }
        }.first { it == true }
        return _results.value.filterKeys { it in productIds }
    }


    open suspend fun queryProductDetails(productIds: List<String>): Map<String, Result<RawStoreProduct>> {
        // Wait for connection
        println("!! Waiting for connection ${Thread.currentThread().name}")
        _isConnected.first { it }
        println("!! Connected ${Thread.currentThread().name}")


        val deferred = CompletableDeferred<Map<String, Result<RawStoreProduct>>>()

        val skuList = ArrayList<String>()
        skuList.addAll(productIds)
        val params = SkuDetailsParams.newBuilder()
        params.setSkusList(skuList).setType(BillingClient.SkuType.SUBS)

        println("!! Querying product details for ${productIds.size} products, prodcuts: ${productIds},  ${Thread.currentThread().name}")

        billingClient.querySkuDetailsAsync(params.build()) { billingResult, skuDetailsList ->

            println("!! Got product details for ${productIds.size} products, prodcuts: ${productIds}, billingResult: ${billingResult}, skuDetailsList: ${skuDetailsList}  ${Thread.currentThread().name}\"")

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {

                var foundProducts = skuDetailsList.map { it.sku }
                var missingProducts = productIds.filter { !foundProducts.contains(it) }

                var results = skuDetailsList.associateBy { it.sku }
                    .mapValues { Result.Success(RawStoreProduct(it.value)) }
                    .toMutableMap() as MutableMap<String, Result<RawStoreProduct>>

                // For all missing products add error
                missingProducts.forEach { missingProductId ->
                    results[missingProductId] =
                        Result.Error(Exception("Failed to query product details"))
                }

                deferred.complete(
                    results.toMap()
                )
            } else {

                // Fail all of them
                val failed: Map<String, Result<RawStoreProduct>> =
                    productIds.map { it to Result.Error<RawStoreProduct>(Exception("Failed to query product details")) }
                        .toMap()

                deferred.complete(failed)
            }
        }

        val value = deferred.await()
        println("!! Returning product details for ${productIds.size} products, prodcuts: ${productIds}, value: ${value}  ${Thread.currentThread().name}\"")
        return value
    }

    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
        println("!!! onPurchasesUpdated $p0 $p1")
    }


    override suspend fun products(
        identifiers: Set<String>,
        paywallName: String?
    ): Set<StoreProduct> {
        val productResults = _products(identifiers.toList())
        return productResults.values.mapNotNull {
            when (it) {
                is Result.Success -> StoreProduct(it.value) // Assuming RawStoreProduct can be converted to StoreProduct
                else -> null
            }
        }.toSet()
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