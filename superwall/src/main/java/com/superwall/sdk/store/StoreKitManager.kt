package com.superwall.sdk.store

import LogLevel
import LogScope
import Logger
import android.content.Context
import com.superwall.sdk.dependencies.SharedBillingClientWrapperFactory
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallProducts
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductType
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.product.receipt.ReceiptManager
import com.superwall.sdk.store.coordinator.ProductsFetcher
import com.superwall.sdk.store.products.GooglePlayProductsFetcher

class StoreKitManager(
    private val context: Context,
    val purchaseController: InternalPurchaseController,
    var wrapperFactory: SharedBillingClientWrapperFactory
) : ProductsFetcher {
    private val productFetcher = GooglePlayProductsFetcher(wrapperFactory)
    private val receiptManager by lazy { ReceiptManager(delegate = this) }

    var productsById: MutableMap<String, StoreProduct> = mutableMapOf()

    private data class ProductProcessingResult(
        val productIdsToLoad: Set<String>,
        val substituteProductsById: Map<String, StoreProduct>,
        val products: List<Product>
    )

    suspend fun getProductVariables(paywall: Paywall): List<ProductVariable> {
        val output = getProducts(paywall.productIds, paywall.name)

        val variables = paywall.products.mapNotNull { product ->
            output.productsById[product.id]?.let { storeProduct ->
                ProductVariable(
                    type = product.type,
                    attributes = storeProduct.attributes
                )
            }
        }
        return variables
    }

    suspend fun getProducts(
        responseProductIds: List<String>,
        paywallName: String? = null,
        responseProducts: List<Product> = emptyList(),
        substituteProducts: PaywallProducts? = null
    ): GetProductsResponse {
        val processingResult = removeAndStore(
            substituteProducts = substituteProducts,
            responseProductIds,
            responseProducts = responseProducts
        )

        val products = products(
            identifiers = processingResult.productIdsToLoad,
            paywallName
        )

        val productsById = processingResult.substituteProductsById.toMutableMap()

        for (product in products) {
            val productIdentifier = product.productIdentifier
            productsById[productIdentifier] = product
            this.productsById[productIdentifier] = product
        }

        return GetProductsResponse(productsById, processingResult.products)
    }

    private fun removeAndStore(
        substituteProducts: PaywallProducts?,
        responseProductIds: List<String>,
        responseProducts: List<Product>
    ): ProductProcessingResult {
        var responseProductIds = responseProductIds.toMutableList()
        var substituteProductsById: MutableMap<String, StoreProduct> = mutableMapOf()
        var products: MutableList<Product> = responseProducts.toMutableList()

        fun storeAndSubstitute(product: StoreProduct, type: ProductType, index: Int) {
            val id = product.productIdentifier
            substituteProductsById[id] = product
            this.productsById[id] = product
            val product = Product(type = type, id = id)
            // Replacing this swift line
            // products[guarded: index] = product
            if (index < products.size && index >= 0) {
                products[index] = product
            }
            if (index < responseProductIds.size && index >= 0) {
                responseProductIds.removeAt(index)
            }
        }

        substituteProducts?.primary?.let {
            storeAndSubstitute(it, ProductType.PRIMARY, 0)
        }
        substituteProducts?.secondary?.let {
            storeAndSubstitute(it, ProductType.SECONDARY, 1)
        }
        substituteProducts?.tertiary?.let {
            storeAndSubstitute(it, ProductType.TERTIARY, 2)
        }

        return ProductProcessingResult(
            productIdsToLoad = responseProductIds.toSet(),
            substituteProductsById = substituteProductsById,
            products = products
        )
    }

    suspend fun refreshReceipt() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.storeKitManager, // Rename this scope to reflect Billing Manager
            message = "Refreshing Google Play receipt."
        )
        receiptManager.refreshReceipt()
    }

    suspend fun loadPurchasedProducts() {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.storeKitManager, // Rename this scope to reflect Billing Manager
            message = "Loading purchased products from the Google Play receipt."
        )
        receiptManager.loadPurchasedProducts()
    }

    suspend fun isFreeTrialAvailable(product: StoreProduct): Boolean {
        return receiptManager.isFreeTrialAvailable(product)
    }

    override suspend fun products(
        identifiers: Set<String>,
        paywallName: String?
    ): Set<StoreProduct> {
        return productFetcher.products(
            identifiers = identifiers,
            paywallName
        )
    }
}

