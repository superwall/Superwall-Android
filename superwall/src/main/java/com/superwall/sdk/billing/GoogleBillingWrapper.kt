package com.superwall.sdk.billing

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.Result
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

internal const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
internal const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 16L * 1000L

class GoogleBillingWrapper(
    val context: Context,
    val mainHandler: Handler = Handler(Looper.getMainLooper()),
    val appLifecycleObserver: AppLifecycleObserver,
) : PurchasesUpdatedListener,
    BillingClientStateListener {
    companion object {
        private val productsCache = ConcurrentHashMap<String, Result<StoreProduct>>()
    }

    @get:Synchronized
    @set:Synchronized
    @Volatile
    var billingClient: BillingClient? = null

    private val serviceRequests =
        ConcurrentLinkedQueue<Pair<(connectionError: BillingError?) -> Unit, Long?>>()

    // how long before the data source tries to reconnect to Google play
    private var reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS

    @get:Synchronized
    @set:Synchronized
    private var reconnectionAlreadyScheduled = false

    // Setup mutable state flow for purchase results
    private val purchaseResults = MutableStateFlow<InternalPurchaseResult?>(null)

    internal val IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE = "Google Play In-app Billing API version is less than 3"

    init {
        startConnectionOnMainThread()
    }

    private fun executePendingRequests() {
        synchronized(this@GoogleBillingWrapper) {
            while (billingClient?.isReady == true) {
                serviceRequests.poll()?.let { (request, delayMilliseconds) ->
                    if (delayMilliseconds != null) {
                        mainHandler.postDelayed(
                            { request(null) },
                            delayMilliseconds,
                        )
                    } else {
                        mainHandler.post { request(null) }
                    }
                } ?: break
            }
        }
    }

    fun startConnectionOnMainThread(delayMilliseconds: Long = 0) {
        mainHandler.postDelayed(
            { startConnection() },
            delayMilliseconds,
        )
    }

    fun startConnection() {
        synchronized(this@GoogleBillingWrapper) {
            if (billingClient == null) {
                billingClient =
                    BillingClient
                        .newBuilder(context)
                        .setListener(this@GoogleBillingWrapper)
                        .enablePendingPurchases()
                        .build()
            }

            reconnectionAlreadyScheduled = false

            billingClient?.let {
                if (!it.isReady) {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.productsManager,
                        "Starting billing client",
                    )
                    try {
                        it.startConnection(this)
                    } catch (e: IllegalStateException) {
                        Logger.debug(
                            LogLevel.error,
                            LogScope.productsManager,
                            "IllegalStateException when connecting to billing client: ${e.message}",
                        )
                        sendErrorsToAllPendingRequests(BillingError.IllegalStateException)
                    }
                }
            }
        }
    }

    /**
     * Gets the StoreProduct(s) for the given list of product ids for all types.
     *
     * Coroutine friendly version of [getProducts].
     *
     * @param [productIds] List of productIds
     *
     * @throws [BillingError] if there's an error retrieving the products.
     * @return A list of [StoreProduct] with the products that have been able to be fetched from the store successfully.
     * Not found products will be ignored.
     */
    @JvmSynthetic
    @Throws(Throwable::class)
    suspend fun awaitGetProducts(fullProductIds: Set<String>): Set<StoreProduct> {
        // Get the cached products. If any are a failure, we throw an error.
        val cachedProducts =
            fullProductIds
                .mapNotNull { fullProductId ->
                    productsCache[fullProductId]?.let { result ->
                        when (result) {
                            is Result.Success -> result.value
                            is Result.Failure -> throw result.error
                        }
                    }
                }.toSet()

        // If all products are found in cache, return them directly
        if (cachedProducts.size == fullProductIds.size) {
            return cachedProducts
        }

        // Determine which product IDs are not in cache
        val missingFullProductIds = fullProductIds - cachedProducts.map { it.fullIdentifier }.toSet()

        return suspendCoroutine { continuation ->
            getProducts(
                missingFullProductIds,
                object : GetStoreProductsCallback {
                    override fun onReceived(storeProducts: Set<StoreProduct>) {
                        // Update cache with fetched products and collect their identifiers
                        val foundProductIds =
                            storeProducts.map { product ->
                                productsCache[product.fullIdentifier] = Result.Success(product)
                                product.fullIdentifier
                            }

                        // Identify and handle missing products
                        missingFullProductIds.filterNot { it in foundProductIds }.forEach { fullProductId ->
                            productsCache[fullProductId] = Result.Failure(Exception("Failed to query product details for $fullProductId"))
                        }

                        // Combine cached products (now including the newly fetched ones) with the fetched products
                        val allProducts = cachedProducts + storeProducts
                        continuation.resume(allProducts)
                    }

                    override fun onError(error: BillingError) {
                        // Identify and handle missing products
                        missingFullProductIds.forEach { fullProductId ->
                            productsCache[fullProductId] = Result.Failure(error)
                        }
                        continuation.resumeWithException(error)
                    }
                },
            )
        }
    }

    private fun getProducts(
        fullProductIds: Set<String>,
        callback: GetStoreProductsCallback,
    ) {
        val types = setOf(ProductType.SUBS, ProductType.INAPP)
        val decomposedProductDetailsBySubscriptionId: MutableMap<String, MutableList<DecomposedProductIds>> =
            mutableMapOf()

        // TODO: CHANGE THIS BECAUSE DECOMPOSING THE PRODUCT ID WON'T WORK NOW
        val subscriptionIds = mutableSetOf<String>()
        // Decompose the subscriptionId into its constituents and create mapping of
        // subscriptionId -> one or more decompositions.
        fullProductIds.forEach { fullProductId ->
            // TODO: Swap this out for PlayStoreProduct
            val decomposedProductIds = DecomposedProductIds.from(fullProductId)
            val subscriptionId = decomposedProductIds.subscriptionId
            subscriptionIds.add(subscriptionId)
            decomposedProductDetailsBySubscriptionId
                .getOrPut(subscriptionId) { mutableListOf() }
                .add(decomposedProductIds)
        }

        getProductsOfTypes(
            subscriptionIds = subscriptionIds,
            types = types,
            collectedStoreProducts = emptySet(),
            decomposedProductIdsBySubscriptionId = decomposedProductDetailsBySubscriptionId,
            callback =
                object : GetStoreProductsCallback {
                    override fun onReceived(storeProducts: Set<StoreProduct>) {
                        callback.onReceived(storeProducts)
                    }

                    override fun onError(error: BillingError) {
                        callback.onError(error)
                    }
                },
        )
    }

    private fun getProductsOfTypes(
        subscriptionIds: Set<String>,
        types: Set<String>,
        collectedStoreProducts: Set<StoreProduct>,
        decomposedProductIdsBySubscriptionId: MutableMap<String, MutableList<DecomposedProductIds>>,
        callback: GetStoreProductsCallback,
    ) {
        val typesRemaining = types.toMutableSet()
        val type = typesRemaining.firstOrNull()?.also { typesRemaining.remove(it) }

        type?.let {
            queryProductDetailsAsync(
                productType = it,
                subscriptionIds = subscriptionIds,
                decomposedProductIdsBySubscriptionId = decomposedProductIdsBySubscriptionId,
                onReceive = { storeProducts ->
                    dispatch {
                        getProductsOfTypes(
                            subscriptionIds,
                            typesRemaining,
                            collectedStoreProducts = collectedStoreProducts + storeProducts,
                            decomposedProductIdsBySubscriptionId = decomposedProductIdsBySubscriptionId,
                            callback,
                        )
                    }
                },
                onError = {
                    dispatch {
                        callback.onError(it)
                    }
                },
            )
        } ?: run {
            callback.onReceived(collectedStoreProducts)
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Thread.currentThread() != Looper.getMainLooper().thread) {
            val handler = mainHandler ?: Handler(Looper.getMainLooper())
            handler.post(action)
        } else {
            action()
        }
    }

    private fun queryProductDetailsAsync(
        productType: String,
        subscriptionIds: Set<String>,
        decomposedProductIdsBySubscriptionId: MutableMap<String, MutableList<DecomposedProductIds>>,
        onReceive: (List<StoreProduct>) -> Unit,
        onError: (BillingError) -> Unit,
    ) {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.productsManager,
            message = "Requesting products from the store with identifiers: ${subscriptionIds.joinToString()}",
        )

        val useCase =
            QueryProductDetailsUseCase(
                QueryProductDetailsUseCaseParams(
                    subscriptionIds = subscriptionIds,
                    decomposedProductIdsBySubscriptionId = decomposedProductIdsBySubscriptionId,
                    productType = productType,
                    appInBackground = appLifecycleObserver.isInBackground.value,
                ),
                onReceive,
                onError,
                ::withConnectedClient,
                ::executeRequestOnUIThread,
            )
        useCase.run()
    }

    @Synchronized
    private fun executeRequestOnUIThread(
        delayMilliseconds: Long? = null,
        request: (BillingError?) -> Unit,
    ) {
        serviceRequests.add(request to delayMilliseconds)
        if (billingClient?.isReady == false) {
            startConnectionOnMainThread()
        } else {
            executePendingRequests()
        }
    }

    /**
     * Retries the billing service connection with exponential backoff, maxing out at the time
     * specified by RECONNECT_TIMER_MAX_TIME_MILLISECONDS.
     *
     * This prevents ANRs, see https://github.com/android/play-billing-samples/issues/310
     */
    private fun retryBillingServiceConnectionWithExponentialBackoff() {
        if (reconnectionAlreadyScheduled) {
            Logger.debug(
                LogLevel.error,
                LogScope.productsManager,
                "Billing client retry already scheduled.",
            )
        } else {
            Logger.debug(
                LogLevel.error,
                LogScope.productsManager,
                "Billing client disconnected, retrying in $reconnectMilliseconds milliseconds",
            )
            reconnectionAlreadyScheduled = true
            startConnectionOnMainThread(reconnectMilliseconds)
            reconnectMilliseconds =
                min(
                    reconnectMilliseconds * 2,
                    RECONNECT_TIMER_MAX_TIME_MILLISECONDS,
                )
        }
    }

    override fun onBillingServiceDisconnected() {
        Logger.debug(
            LogLevel.debug,
            LogScope.productsManager,
            "Billing client disconnected",
        )
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        mainHandler.post {
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.productsManager,
                        "Billing client connected",
                    )
                    executePendingRequests()
                    reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
                    trackProductDetailsNotSupportedIfNeeded()
                }

                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                -> {
                    val originalErrorMessage =
                        "DebugMessage: ${billingResult.debugMessage} " +
                            "ErrorCode: ${billingResult.responseCode}."

                    /**
                     * We check for cases when Google sends Google Play In-app Billing API version is less than 3
                     * as a debug message. Version 3 is from 2012, so the message is a bit useless.
                     * We have detected this message in several cases:
                     *
                     * - When there's no Google account configured in the device
                     * - When there's no Play Store (this happens in incorrectly configured emulators)
                     * - When language is changed in the device and Play Store caches get corrupted. Opening the
                     * Play Store or clearing its caches would fix this case.
                     * See https://github.com/RevenueCat/purchases-android/issues/1288
                     */
                    val error =
                        if (billingResult.debugMessage == IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE) {
                            val message =
                                "Billing is not available in this device. Make sure there's an " +
                                    "account configured in Play Store. Reopen the Play Store or clean its caches if this " +
                                    "keeps happening. " +
                                    "Original error message: $originalErrorMessage"
                            BillingError.BillingNotAvailable(message)
                        } else {
                            val message =
                                "Billing is not available in this device. " +
                                    "Original error message: $originalErrorMessage"
                            BillingError.BillingNotAvailable(message)
                        }

                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        error.message ?: "Billing is not available in this device. ${billingResult.debugMessage}",
                    )
                    // The calls will fail with an error that will be surfaced. We want to surface these errors
                    // Can't call executePendingRequests because it will not do anything since it checks for isReady()
                    sendErrorsToAllPendingRequests(error)
                }

                BillingClient.BillingResponseCode.ERROR,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.USER_CANCELED,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.NETWORK_ERROR,
                -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, retrying: ${billingResult.responseCode}",
                    )
                    retryBillingServiceConnectionWithExponentialBackoff()
                }

                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
                BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
                -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, item not supported or unavailable: ${billingResult.responseCode}",
                    )
                }

                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                    // Billing service is already trying to connect. Don't do anything.
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, developer error: ${billingResult.responseCode}",
                    )
                }
            }
        }
    }

    fun withConnectedClient(receivingFunction: BillingClient.() -> Unit) {
        billingClient?.takeIf { it.isReady }?.let {
            it.receivingFunction()
        } ?: Logger.debug(
            LogLevel.error,
            LogScope.productsManager,
            "Billing client not ready",
        )
    }

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        println("onPurchasesUpdated: $result")
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                println("Purchase: $purchase")
                CoroutineScope(Dispatchers.IO).launch {
                    purchaseResults.emit(
                        InternalPurchaseResult.Purchased(purchase),
                    )
                }
            }
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            CoroutineScope(Dispatchers.IO).launch {
                purchaseResults.emit(InternalPurchaseResult.Cancelled)
            }

            println("User cancelled purchase")
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                purchaseResults.emit(InternalPurchaseResult.Failed(Exception(result.responseCode.toString())))
            }
            println("Purchase failed")
        }
    }

    suspend fun getLatestTransaction(factory: StoreTransactionFactory): StoreTransaction? {
        // Get the latest from purchaseResults
        purchaseResults.asStateFlow().filter { it != null }.first().let { purchaseResult ->
            return when (purchaseResult) {
                is InternalPurchaseResult.Purchased -> {
                    return factory.makeStoreTransaction(purchaseResult.purchase)
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

    @Synchronized
    private fun sendErrorsToAllPendingRequests(error: BillingError) {
        while (true) {
            serviceRequests.poll()?.let { (serviceRequest, _) ->
                mainHandler.post {
                    serviceRequest(error)
                }
            } ?: break
        }
    }

    private fun trackProductDetailsNotSupportedIfNeeded() {
        val billingResult = billingClient?.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
        if (
            billingResult != null &&
            billingResult.responseCode == BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
        ) {
            Logger.debug(
                LogLevel.error,
                LogScope.productsManager,
                "Product details not supported: ${billingResult.responseCode} ${billingResult.debugMessage}",
            )
        }
    }
}
