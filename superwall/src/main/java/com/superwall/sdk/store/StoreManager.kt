package com.superwall.sdk.store

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.BillingError
import com.superwall.sdk.billing.DecomposedProductIds
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.PlayStoreProduct
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.product.receipt.ReceiptManager
import com.superwall.sdk.store.coordinator.ProductsFetcher
import com.superwall.sdk.store.testmode.TestModeManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import java.util.Date

class StoreManager(
    val purchaseController: InternalPurchaseController,
    val billing: Billing,
    receiptManagerFactory: () -> ReceiptManager,
    private val track: suspend (InternalSuperwallEvent) -> Unit = {
        Superwall.instance.track(it)
    },
    var testModeManager: TestModeManager? = null,
) : ProductsFetcher,
    StoreKit {
    val receiptManager by lazy(receiptManagerFactory)

    private var productsByFullId: MutableMap<String, ProductState> = mutableMapOf()

    private data class ProductProcessingResult(
        val fullProductIdsToLoad: Set<String>,
        val substituteProductsById: Map<String, StoreProduct>,
        val productItems: List<ProductItem>,
    )

    override suspend fun getProductVariables(
        paywall: Paywall,
        request: PaywallRequest,
    ): List<ProductVariable> {
        val output =
            getProducts(
                paywall = paywall,
                request = request,
            )

        val productAttributes =
            paywall.playStoreProducts.mapNotNull { productItem ->
                output.productsByFullId[productItem.fullProductId]?.let { storeProduct ->
                    ProductVariable(
                        name = productItem.name,
                        attributes = storeProduct.attributes,
                    )
                }
            }

        return productAttributes
    }

    override suspend fun getProductsWithoutPaywall(
        productIds: List<String>,
        substituteProducts: Map<String, StoreProduct>?,
    ): Map<String, StoreProduct> {
        val processingResult =
            removeAndStore(
                substituteProductsByName = substituteProducts,
                fullProductIds = productIds,
                productItems = emptyList(),
            )

        val productsById = processingResult.substituteProductsById.toMutableMap()
        val fetchResult = fetchOrAwaitProducts(processingResult.fullProductIdsToLoad)

        for ((id, product) in fetchResult) {
            productsById[id] = product
        }

        return productsById
    }

    override suspend fun getProducts(
        substituteProducts: Map<String, StoreProduct>?,
        paywall: Paywall,
        request: PaywallRequest?,
    ): GetProductsResponse {
        val processingResult =
            removeAndStore(
                substituteProductsByName = substituteProducts,
                fullProductIds = paywall.productIds,
                productItems = paywall.productItems,
            )

        val productsById = processingResult.substituteProductsById.toMutableMap()

        try {
            val fetchResult = fetchOrAwaitProducts(processingResult.fullProductIdsToLoad)
            for ((id, product) in fetchResult) {
                productsById[id] = product
            }
        } catch (error: Throwable) {
            paywall.productsLoadingInfo.failAt = Date()
            val paywallInfo = paywall.getInfo(request?.eventData)
            val productLoadEvent =
                InternalSuperwallEvent.PaywallProductsLoad(
                    state = InternalSuperwallEvent.PaywallProductsLoad.State.Fail(error.message),
                    paywallInfo = paywallInfo,
                    eventData = request?.eventData,
                )
            track(productLoadEvent)

            // If billing isn't available, make it call the onError handler when requesting
            // a paywall.
            if (error is BillingError.BillingNotAvailable) {
                throw error
            }
        }

        return GetProductsResponse(
            productsByFullId = productsById,
            productItems = processingResult.productItems,
            paywall = paywall,
        )
    }

    private suspend fun fetchOrAwaitProducts(fullProductIds: Set<String>): Map<String, StoreProduct> {
        val states = fullProductIds.associateWith { productsByFullId[it] }

        val cached =
            states.entries
                .mapNotNull { (id, state) -> (state as? ProductState.Loaded)?.let { id to it.product } }
                .toMap()

        val loading =
            states.entries
                .mapNotNull { (_, state) -> (state as? ProductState.Loading)?.deferred }

        val newDeferreds =
            states.entries
                .filter { (_, state) -> state !is ProductState.Loaded && state !is ProductState.Loading }
                .associate { (id, _) ->
                    val deferred = CompletableDeferred<StoreProduct?>()
                    productsByFullId[id] = ProductState.Loading(deferred)
                    id to deferred
                }

        // Await all in-flight products in parallel
        val awaited =
            loading
                .awaitAll()
                .filterNotNull()
                .associateBy { it.fullIdentifier }

        val fetched = fetchNewProducts(newDeferreds)

        return cached + awaited + fetched
    }

    private suspend fun fetchNewProducts(deferreds: Map<String, CompletableDeferred<StoreProduct?>>): Map<String, StoreProduct> {
        if (deferreds.isEmpty()) return emptyMap()

        return try {
            val products = billing.awaitGetProducts(deferreds.keys)
            val fetched = products.associateBy { it.fullIdentifier }

            fetched.forEach { (id, product) ->
                productsByFullId[id] = ProductState.Loaded(product)
                deferreds[id]?.complete(product)
            }

            // Mark products not returned by billing as errors
            (deferreds.keys - fetched.keys).forEach { id ->
                productsByFullId[id] = ProductState.Error(Exception("Product $id not found in store"))
                deferreds[id]?.complete(null)
            }

            fetched
        } catch (error: Throwable) {
            deferreds.forEach { (id, deferred) ->
                productsByFullId[id] = ProductState.Error(error)
                deferred.complete(null)
            }
            throw error
        }
    }

    private fun removeAndStore(
        substituteProductsByName: Map<String, StoreProduct>?,
        fullProductIds: List<String>,
        productItems: List<ProductItem>,
    ): ProductProcessingResult {
        val fullProductIdsToLoad = fullProductIds.toMutableList()
        val substituteProductsByFullId: MutableMap<String, StoreProduct> = mutableMapOf()
        val productItems: MutableList<ProductItem> = productItems.toMutableList()

        substituteProductsByName?.let { substituteProducts ->
            // Otherwise, iterate over each substitute product
            for ((name, product) in substituteProducts) {
                val fullProductId = product.fullIdentifier

                // Map substitute product by its ID.
                substituteProductsByFullId[fullProductId] = product

                // Store the substitute product by id in the class' dictionary
                cacheProduct(fullProductId, product)
                val decomposedProductIds = DecomposedProductIds.from(product.fullIdentifier)

                // Search for an existing product with specified name
                productItems.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { index ->
                    // Update the product ID at the found index
                    val storeProduct =
                        ProductItem.StoreProductType.PlayStore(
                            PlayStoreProduct(
                                productIdentifier = decomposedProductIds.subscriptionId,
                                basePlanIdentifier = decomposedProductIds.basePlanId ?: "",
                                offer = decomposedProductIds.offerType.toOffer(),
                            ),
                        )
                    productItems[index] =
                        ProductItem(
                            name = productItems[index].name,
                            entitlements = productItems[index].entitlements,
                            type = storeProduct,
                            compositeId = storeProduct.product.fullIdentifier,
                        )
                } ?: run {
                    val storeProduct =
                        ProductItem.StoreProductType.PlayStore(
                            PlayStoreProduct(
                                productIdentifier = decomposedProductIds.subscriptionId,
                                basePlanIdentifier = decomposedProductIds.basePlanId ?: "",
                                offer = decomposedProductIds.offerType.toOffer(),
                            ),
                        )
                    // If no existing product found, just append to the list.
                    productItems.add(
                        ProductItem(
                            name = name,
                            entitlements = emptySet(),
                            type = storeProduct,
                            compositeId = storeProduct.product.fullIdentifier,
                        ),
                    )
                }

                // Make sure we don't load the substitute product id
                fullProductIdsToLoad.removeAll { it == fullProductId }
            }
        }

        return ProductProcessingResult(
            fullProductIdsToLoad = fullProductIdsToLoad.toSet(),
            substituteProductsById = substituteProductsByFullId,
            productItems = productItems,
        )
    }

    override fun refreshReceipt() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.storeKitManager, // Rename this scope to reflect Billing Manager
            message = "Refreshing Google Play receipt.",
        )
        receiptManager.refreshReceipt()
    }

    override suspend fun loadPurchasedProducts(serverEntitlementsByProductId: Map<String, Set<Entitlement>>) {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.storeKitManager, // Rename this scope to reflect Billing Manager
            message = "Loading purchased products from the Google Play receipt.",
        )
        receiptManager.loadPurchasedProducts(serverEntitlementsByProductId)
    }

    override fun cacheProduct(
        fullProductIdentifier: String,
        storeProduct: StoreProduct,
    ) {
        val existing = productsByFullId[fullProductIdentifier]
        productsByFullId[fullProductIdentifier] = ProductState.Loaded(storeProduct)
        // Complete any pending deferred so awaiters get the product
        if (existing is ProductState.Loading) {
            existing.deferred.complete(storeProduct)
        }
    }

    override fun getProductFromCache(productId: String): StoreProduct? {
        // Check test products first when in test mode
        testModeManager?.let { manager ->
            if (manager.isTestMode) {
                manager.testProductsByFullId[productId]?.let { return it }
            }
        }
        return (productsByFullId[productId] as? ProductState.Loaded)?.product
    }

    override fun hasCached(productId: String): Boolean {
        testModeManager?.let { manager ->
            if (manager.isTestMode && manager.testProductsByFullId.containsKey(productId)) {
                return true
            }
        }
        return productsByFullId[productId] is ProductState.Loaded
    }

    override suspend fun consume(purchaseToken: String): Result<String> = billing.consume(purchaseToken)

    @Throws(Throwable::class)
    override suspend fun products(identifiers: Set<String>): Set<StoreProduct> = billing.awaitGetProducts(identifiers)
}
