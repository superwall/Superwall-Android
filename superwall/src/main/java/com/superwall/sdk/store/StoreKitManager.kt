package com.superwall.sdk.store

import LogLevel
import LogScope
import Logger
import android.content.Context
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallProducts
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductType
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.product.receipt.ReceiptManager
import com.superwall.sdk.store.coordinator.ProductsFetcher
import com.superwall.sdk.store.products.GooglePlayProductsFetcher

/*
class StoreKitManager(private val context: Context) : StoreKitManagerInterface {
    private val fetcher: GooglePlayProductsFetcher = GooglePlayProductsFetcher(context)

    override val productsById: Map<String, StoreProduct>
        get() = TODO("Not yet implemented")

    override suspend fun getProductVariables(paywall: Paywall): List<ProductVariable> {
        TODO("Not yet implemented")
    }

//    data class GetProductsResponse(
//        val productsById: Map<String, StoreProduct>,
//        val products: List<Product>
//    )

    override suspend fun getProducts(
        responseProductIds: List<String>,
        paywallName: String?,
        responseProducts: List<Product>,
        substituteProducts: PaywallProducts?
    ): GetProductsResponse {
        var productsById = mutableMapOf<String, StoreProduct>()
        println("!! responseProductIds: $responseProductIds")
        val products = fetcher.products(responseProductIds)
        println("!! products: $products")

        for (product in products) {
            when(product.value)  {
                is GooglePlayProductsFetcher.Result.Success -> {
                    val rawStoreProduct = (product.value as GooglePlayProductsFetcher.Result.Success).value
                    println("!! rawStoreProduct: $rawStoreProduct")
                    productsById[product.key] = StoreProduct(rawStoreProduct)
                } else -> {
                    // TODO: ??
                }
            }
        }

        return GetProductsResponse(productsById, responseProducts)
    }

    override suspend fun tryToRestore(paywallViewController: PaywallViewController) {
//        TODO("Not yet implemented")
    }

    override suspend fun processRestoration(
        restorationResult: RestorationResult,
        paywallViewController: PaywallViewController
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun refreshReceipt() {
        TODO("Not yet implemented")
    }

    override suspend fun loadPurchasedProducts() {
        TODO("Not yet implemented")
    }

    override suspend fun isFreeTrialAvailable(product: StoreProduct): Boolean {
        // TODO: Implement this
        return false
    }

    override suspend fun products(
        identifiers: Set<String>,
        paywallName: String?
    ): Set<StoreProduct> {
        TODO("Not yet implemented")
    }
}
*/

class StoreKitManager(
    private val context: Context,
    val purchaseController: InternalPurchaseController,
    val productFetcher: GooglePlayProductsFetcher
) : ProductsFetcher {
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
            val productIdentifier = product.fullIdentifier
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
            val id = product.fullIdentifier
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