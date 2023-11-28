package com.superwall.sdk.store.products

import android.content.Context
import com.android.billingclient.api.*
import com.superwall.sdk.billing.GoogleBillingWrapper
import com.superwall.sdk.store.abstractions.product.OfferType
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.SerializableProductDetails
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductsFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class Result<T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Error<T>(val error: Throwable) : Result<T>()
    data class Waiting<T>(val startedAt: Int) : Result<T>()
}

private const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
private const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L // 15 minutes

data class ProductIds(
    val basePlanId: String?,
    val offerType: OfferType?
) {
    companion object {
        fun from(components: List<String>): ProductIds {
            val basePlanId = components.getOrNull(1)
            val offerId = components.getOrNull(2)
            var offerType: OfferType? = null

            if (offerId == "sw-auto") {
                offerType = OfferType.Auto
            } else if (offerId != null) {
                offerType = OfferType.Offer(id = offerId)
            }
            return ProductIds(basePlanId, offerType)
        }
    }
}

open class GooglePlayProductsFetcher(var context: Context, var billingWrapper: GoogleBillingWrapper) : ProductsFetcher,
    PurchasesUpdatedListener {

    sealed class Result<T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Error<T>(val error: Throwable) : Result<T>()
        data class Waiting<T>(val startedAt: Int) : Result<T>()
    }


    // Create a map with product id to status
    private val _results = MutableStateFlow<Map<String, Result<RawStoreProduct>>>(emptyMap())
    val results: StateFlow<Map<String, Result<RawStoreProduct>>> = _results
    var productIdsBySubscriptionId: MutableMap<String, ProductIds> = mutableMapOf()

    // Create a supervisor job
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected fun request(productIds: List<String>) {
        scope.launch {
            val currentResults = _results.value

            println("!!! Current results ${currentResults.size}")

            var subscriptionIdsToLoad: List<String> = emptyList()
            productIds.forEach { productId ->
                val result = currentResults[productId]
                println("Result for $productId is $result ${Thread.currentThread().name}")
                if (result == null) {
                    val components = productId.split(":")
                    val subscriptionId = components.getOrNull(0) ?: ""
                    productIdsBySubscriptionId[subscriptionId] = ProductIds.from(components)
                    subscriptionIdsToLoad = subscriptionIdsToLoad + subscriptionId
                }
            }

            print("!!! Requesting ${subscriptionIdsToLoad.size} products")

            if (!subscriptionIdsToLoad.isEmpty()) {
                _results.emit(
                    _results.value + subscriptionIdsToLoad.map {
                        it to Result.Waiting(
                            startedAt = System.currentTimeMillis().toInt()
                        )
                    }
                )

                println("!! Querying product details for ${subscriptionIdsToLoad.size} products, prodcuts: ${subscriptionIdsToLoad} ${Thread.currentThread().name}")
                val networkResult = runBlocking {
                    queryProductDetails(subscriptionIdsToLoad)
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
        if (productIds.isEmpty()) {
            return emptyMap()
        }

        // Make sure we've tried to connect
        val deferredSubs = CompletableDeferred<Map<String, Result<RawStoreProduct>>>()
        val deferredInApp = CompletableDeferred<Map<String, Result<RawStoreProduct>>>()

        val subsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()

        val inAppParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()

        println("!! Querying subscription product details for ${productIds.size} products, products: ${productIds},  ${Thread.currentThread().name}")
        billingWrapper.waitForConnectedClient{
            queryProductDetailsAsync(subsParams) { billingResult, productDetailsList ->
                val resultMap =
                    handleProductDetailsResponse(productIds, billingResult, productDetailsList)
                deferredSubs.complete(resultMap) // Or deferredInApp depending on the product type
            }
        }

        println("!! Querying in-app product details for ${productIds.size} products, products: ${productIds},  ${Thread.currentThread().name}")
        billingWrapper.waitForConnectedClient {
            queryProductDetailsAsync(inAppParams) { billingResult, productDetailsList ->
                val resultMap =
                    handleProductDetailsResponse(productIds, billingResult, productDetailsList)
                deferredInApp.complete(resultMap) // Or deferredInApp depending on the product type
            }
        }

        val subsResults = deferredSubs.await()
        val inAppResults = deferredInApp.await()

        val combinedResults = mutableMapOf<String, Result<RawStoreProduct>>()

        // First, populate the map with successes from both sets
        for ((key, value) in subsResults) {
            if (value is Result.Success) {
                combinedResults[key] = value
            }
        }
        for ((key, value) in inAppResults) {
            if (value is Result.Success) {
                combinedResults[key] = value
            }
        }

        // Now, populate any remaining keys with failures, but only if the key hasn't been populated already
        for ((key, value) in subsResults) {
            combinedResults.getOrPut(key) { value }
        }
        for ((key, value) in inAppResults) {
            combinedResults.getOrPut(key) { value }
        }

        println("!! Returning product details for ${productIds.size} products ${combinedResults}, ${Thread.currentThread().name}\"")

        return combinedResults
    }

    private fun handleProductDetailsResponse(
        productIds: List<String>,
        billingResult: BillingResult,
        productDetailsList: List<ProductDetails>?
    ): Map<String, Result<RawStoreProduct>> {
        println("!! Got product details for ${productIds.size} products, products: ${productIds}, billingResult: ${billingResult}, productDetailsList: ${productDetailsList}  ${Thread.currentThread().name}\"")

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList != null) {
            val foundProducts = productDetailsList.map { it.productId }
            val missingProducts = productIds.filter { !foundProducts.contains(it) }
            val results = productDetailsList.associateBy { it.productId }
                .mapValues { (_, productDetails) ->
                    val productIds = productIdsBySubscriptionId[productDetails.productId]
                    Result.Success(
                        RawStoreProduct(
                            underlyingProductDetails = productDetails,
                            basePlanId = productIds?.basePlanId,
                            offerType = productIds?.offerType
                         )
                    )
                }
                .toMutableMap() as MutableMap<String, Result<RawStoreProduct>>

            missingProducts.forEach { missingProductId ->
                results[missingProductId] = Result.Error(Exception("Failed to query product details"))
            }

            return results.toMap()
        } else {
            return productIds.map { it to Result.Error<RawStoreProduct>(Exception("Failed to query product details")) }
                .toMap()
        }
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
}