package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import java.util.concurrent.atomic.AtomicBoolean

internal data class QueryProductDetailsUseCaseParams(
    val subscriptionIds: Set<String>,
    val decomposedProductIdsBySubscriptionId: MutableMap<String, MutableList<DecomposedProductIds>>,
    val productType: String,
    override val appInBackground: Boolean,
) : UseCaseParams

/**
 * This class is used to construct a query to get `ProductsDetails` from a list of product ids.
 */
internal class QueryProductDetailsUseCase(
    private val useCaseParams: QueryProductDetailsUseCaseParams,
    val onReceive: (List<StoreProduct>) -> Unit,
    val onError: (BillingError) -> Unit,
    val withConnectedClient: (BillingClient.() -> Unit) -> Unit,
    executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : BillingClientUseCase<List<ProductDetails>>(useCaseParams, onError, executeRequestOnUIThread) {
    override fun executeAsync() {
        val nonEmptyProductIds = useCaseParams.subscriptionIds.filter { it.isNotEmpty() }.toSet()

        if (nonEmptyProductIds.isEmpty()) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.productsManager,
                message = "productId list is empty, skipping queryProductDetailsAsync call",
            )
            onReceive(emptyList())
            return
        }
        withConnectedClient {
            val googleType = useCaseParams.productType
            val params = googleType.buildQueryProductDetailsParams(nonEmptyProductIds)

            queryProductDetailsAsyncEnsuringOneResponse(
                this,
                params,
                ::processResult,
            )
        }
    }

    /**
     * Gets called after `processResult` sees a success status. Turns `ProductDetails` into
     * `StoreProduct`.
     */
    override fun onOk(received: List<ProductDetails>) {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.productsManager,
            message = "Products request finished for ${useCaseParams.subscriptionIds.joinToString()}",
        )
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.productsManager,
            message = "Retrieved productDetailsList: ${received.joinToString { it.toString() }}",
        )
        received.takeUnless { it.isEmpty() }?.forEach {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.productsManager,
                message = "${it.productId} - $it",
            )
        }

        val storeProducts =
            received
                .flatMap { productDetails ->
                    useCaseParams.decomposedProductIdsBySubscriptionId[productDetails.productId]?.map { productId ->
                        val rawStoreProduct =
                            RawStoreProduct(
                                underlyingProductDetails = productDetails,
                                fullIdentifier = productId.fullId ?: "",
                                basePlanId = productId.basePlanId,
                                offerType = productId.offerType,
                            )
                        StoreProduct(rawStoreProduct)
                    } ?: emptyList()
                }

        onReceive(storeProducts)
    }

    @Synchronized
    private fun queryProductDetailsAsyncEnsuringOneResponse(
        billingClient: BillingClient,
        params: QueryProductDetailsParams,
        listener: ProductDetailsResponseListener,
    ) {
        val hasResponded = AtomicBoolean(false)
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (hasResponded.getAndSet(true)) {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.productsManager,
                    message =
                        "BillingClient queryProductDetails has returned more than once, " +
                            "with result ${billingResult.responseCode}",
                )
                return@queryProductDetailsAsync
            }
            listener.onProductDetailsResponse(billingResult, productDetailsList)
        }
    }
}
