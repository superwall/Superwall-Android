package com.superwall.sdk.store.products

import android.content.Context
import com.android.billingclient.api.*
import com.android.billingclient.api.UserChoiceDetails.Product
import com.superwall.sdk.billing.GoogleBillingWrapper
import com.superwall.sdk.store.abstractions.product.OfferType
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

private const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
private const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L // 15 minutes

data class ProductIds(
    val subscriptionId: String,
    val basePlanId: String?,
    val offerType: OfferType?,
    val fullId: String
) {
    companion object {
        fun from(productId: String): ProductIds {
            val components = productId.split(":")
            val subscriptionId = components.getOrNull(0) ?: ""
            val basePlanId = components.getOrNull(1)
            val offerId = components.getOrNull(2)
            var offerType: OfferType? = null

            if (offerId == "sw-auto") {
                offerType = OfferType.Auto
            } else if (offerId != null) {
                offerType = OfferType.Offer(id = offerId)
            }
            return ProductIds(
                subscriptionId = subscriptionId,
                basePlanId = basePlanId,
                offerType = offerType,
                fullId = productId
            )
        }
    }
}

open class GooglePlayProductsFetcher(
    var context: Context,
    var billingWrapper: GoogleBillingWrapper
) : ProductsFetcher,
    PurchasesUpdatedListener {

    sealed class Result<T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Error<T>(val error: Throwable) : Result<T>()
        data class Waiting<T>(val startedAt: Int) : Result<T>()
    }


    // Create a map with product id to status
    private val _results = MutableStateFlow<Map<String, Result<RawStoreProduct>>>(emptyMap())
    val results: StateFlow<Map<String, Result<RawStoreProduct>>> = _results
    private var productIdsBySubscriptionId: MutableMap<String, MutableList<ProductIds>> = mutableMapOf()

    // Create a supervisor job
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected fun request(productIds: List<String>) {
        scope.launch {
            // Get the current results from _results value
            val currentResults = _results.value
            println("!!! Current results ${currentResults.size}")

            // Initialize a set to hold unique product IDs to be loaded
            var productIdsToLoad: Set<ProductIds> = emptySet()

            // Iterate through each product ID
            productIds.forEach { productId ->
                // Parse the full product ID into a ProductIds object
                val productIds = ProductIds.from(productId)

                // Check if the result for the current full product ID is already available
                val result = currentResults[productIds.fullId]

                // If the result is null, process the product ID
                if (result == null) {
                    val subscriptionId = productIds.subscriptionId
                    // Append the product ID to the list of productIds mapped to the subscription ID.
                    // Products are 1 to many mappings so this is used to map loaded products back to
                    // 1 or more full product ids.
                    productIdsBySubscriptionId.getOrPut(subscriptionId) { mutableListOf() }.add(productIds)
                    // Add the productIds object to the set of productIds to load
                    productIdsToLoad = productIdsToLoad + productIds
                }
            }

            println("!!! Requesting ${productIdsToLoad.size} products")

            // Check if there are any product IDs to load
            if (productIdsToLoad.isNotEmpty()) {
                // Emit a waiting result for each full product id.
                val resultsValue = _results.value + productIdsToLoad.map { it.fullId }.associateWith {
                    Result.Waiting(startedAt = System.currentTimeMillis().toInt())
                }
                _results.emit(resultsValue)

                // Log the querying of product details
                println("!! Querying product details for ${productIdsToLoad.size} products, products: ${productIdsToLoad} ${Thread.currentThread().name}")

                // Perform the network request to get product details using the subscription id.
                val networkResult = runBlocking {
                    val subscriptionIdsToLoad = productIdsToLoad.map { it.subscriptionId }.distinct()
                    queryProductDetails(subscriptionIdsToLoad)
                }
                println("!! networkResult: ${networkResult} ${Thread.currentThread().name}")

                // Emit the updated results to _results
                _results.emit(_results.value + networkResult)
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

    open suspend fun queryProductDetails(subscriptionIds: List<String>): Map<String, Result<RawStoreProduct>> {
        if (subscriptionIds.isEmpty()) {
            return emptyMap()
        }

        // Make sure we've tried to connect
        val deferredSubs = CompletableDeferred<Map<String, Result<RawStoreProduct>>>()
        val deferredInApp = CompletableDeferred<Map<String, Result<RawStoreProduct>>>()

        val subsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                subscriptionIds.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()

        val inAppParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                subscriptionIds.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()

        println("!! Querying subscription product details for ${subscriptionIds.size} products, products: ${subscriptionIds},  ${Thread.currentThread().name}")
        billingWrapper.waitForConnectedClient {
            queryProductDetailsAsync(subsParams) { billingResult, productDetailsList ->
                val resultMap =
                    handleProductDetailsResponse(subscriptionIds, billingResult, productDetailsList)
                deferredSubs.complete(resultMap) // Or deferredInApp depending on the product type
            }
        }

        println("!! Querying in-app product details for ${subscriptionIds.size} products, products: ${subscriptionIds},  ${Thread.currentThread().name}")
        billingWrapper.waitForConnectedClient {
            queryProductDetailsAsync(inAppParams) { billingResult, productDetailsList ->
                val resultMap =
                    handleProductDetailsResponse(subscriptionIds, billingResult, productDetailsList)
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

        println("!! Returning product details for ${subscriptionIds.size} products ${combinedResults}, ${Thread.currentThread().name}\"")

        return combinedResults
    }

    private fun handleProductDetailsResponse(
        subscriptionIds: List<String>,
        billingResult: BillingResult,
        productDetailsList: List<ProductDetails>?
    ): Map<String, Result<RawStoreProduct>> {
        println("!! Got product details for ${subscriptionIds.size} products, products: ${subscriptionIds}, billingResult: ${billingResult}, productDetailsList: ${productDetailsList}  ${Thread.currentThread().name}\"")

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList != null) {
            val foundProducts = productDetailsList.map { it.productId }
            val missingProducts = subscriptionIds.filter { !foundProducts.contains(it) }

            val subscriptionIdToResult = productDetailsList
                .flatMap { productDetails ->
                    productIdsBySubscriptionId[productDetails.productId]?.map { productId ->
                        productId.fullId to Result.Success(
                            RawStoreProduct(
                                underlyingProductDetails = productDetails,
                                fullIdentifier = productId.fullId ?: "",
                                basePlanId = productId.basePlanId,
                                offerType = productId.offerType
                            )
                        )
                    } ?: emptyList()
                }
                .toMap()
                .toMutableMap() as MutableMap<String, Result<RawStoreProduct>>

            val missingProductsString = missingProducts.joinToString(separator = ", ")
            missingProducts.forEach { missingProductId ->
                productIdsBySubscriptionId[missingProductId]?.forEach { product ->
                    subscriptionIdToResult[product.fullId] = Result.Error(Exception("Failed to query product details for $missingProductsString"))
                }
            }

            return subscriptionIdToResult.toMap()
        } else {
            val missingProductsString = subscriptionIds.joinToString(separator = ", ")
            val results: MutableMap<String, Result<RawStoreProduct>> = mutableMapOf()
            subscriptionIds.forEach { subscriptionId ->
                productIdsBySubscriptionId[subscriptionId]?.forEach { product ->
                    results[product.fullId] = Result.Error(Exception("Failed to query product details for $missingProductsString. Billing response code: ${billingResult.responseCode}"))
                }
            }
            return results
        }
    }

    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
        println("!!! onPurchasesUpdated $p0 $p1")
    }

    override suspend fun products(
        identifiers: Set<String>
    ): Set<StoreProduct> {
        val productResults = _products(identifiers.toList())
        return productResults.values.mapNotNull {
            when (it) {
                is Result.Success -> StoreProduct(it.value) // Assuming RawStoreProduct can be converted to StoreProduct
                is Result.Error -> throw it.error
                else -> null
            }
        }.toSet()
    }
}