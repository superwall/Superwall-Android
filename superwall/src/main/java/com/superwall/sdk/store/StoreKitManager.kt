package com.superwall.sdk.store

import android.content.Context
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallProducts
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.products.ProductFetcher

class StoreKitManager(private val context: Context) : StoreKitManagerInterface {
    private val fetcher: ProductFetcher = ProductFetcher(context)

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
        val products = fetcher.products(responseProductIds)

        for (product in products) {
            when(product.value)  {
                is ProductFetcher.Result.Success -> {
                    val rawStoreProduct = (product.value as ProductFetcher.Result.Success).value
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
        TODO("Not yet implemented")
    }

    override suspend fun products(
        identifiers: Set<String>,
        paywallName: String?
    ): Set<StoreProduct> {
        TODO("Not yet implemented")
    }
}
