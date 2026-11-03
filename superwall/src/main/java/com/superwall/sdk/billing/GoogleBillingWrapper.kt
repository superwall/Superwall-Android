package com.superwall.sdk.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.dependencies.HasInternalPurchaseControllerFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.retryOrNull
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
internal const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 16L * 1000L

// Consecutive transient setup failures after which billing is treated as unavailable, so
// requests stop waiting on a connection that may never come (e.g. a broken Play Store).
// Reconnecting carries on in the background and a later successful setup makes it available.
internal const val MAX_TRANSIENT_SETUP_FAILURES = 3

// How long a product query may take, connection time included, before it fails. Play
// normally answers within a couple of seconds; this is a backstop so a callback that
// never arrives can't leave paywalls waiting on their products for the life of the process.
internal const val PRODUCTS_QUERY_TIMEOUT_MS = 15_000L

/** Builds the Play billing client the SDK uses, reporting purchases to [listener]. */
internal fun playBillingClient(context: Context): (PurchasesUpdatedListener) -> BillingClient =
    { listener ->
        BillingClient
            .newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            ).build()
    }

class GoogleBillingWrapper internal constructor(
    val context: Context,
    val ioScope: IOScope,
    val appLifecycleObserver: AppLifecycleObserver,
    val factory: Factory,
    val createBillingClient: (PurchasesUpdatedListener) -> BillingClient,
    private val productDetails: ProductDetailsRepository,
    connectionActor: StateActor<BillingConnectionContext, ConnectionState>,
) : PurchasesUpdatedListener,
    BillingClientStateListener,
    Billing {
    /**
     * Builds a wrapper with its own product cache and billing connection. The SDK builds its
     * wrapper through the internal constructor, from `DependencyContainer`.
     */
    constructor(
        context: Context,
        ioScope: IOScope,
        appLifecycleObserver: AppLifecycleObserver,
        factory: Factory,
        createBillingClient: (PurchasesUpdatedListener) -> BillingClient = playBillingClient(context),
    ) : this(
        context = context,
        ioScope = ioScope,
        appLifecycleObserver = appLifecycleObserver,
        factory = factory,
        createBillingClient = createBillingClient,
        productDetails = ProductDetailsRepository(),
        connectionActor = SequentialActor(ConnectionState(), ioScope),
    )

    companion object {
        private const val QUERY_PURCHASES_TIMEOUT_MS = 10_000L
        private const val QUERY_PURCHASES_MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }

    /** Forgets every product this wrapper fetched, so the next request asks Play again. */
    @androidx.annotation.VisibleForTesting
    internal fun clearProductsCache() {
        productDetails.clear()
    }

    interface Factory :
        HasExternalPurchaseControllerFactory,
        HasInternalPurchaseControllerFactory,
        OptionsFactory

    private val threadHandler = Handler(ioScope)
    private val shouldFinishTransactions: Boolean
        get() = !factory.makeSuperwallOptions().shouldObservePurchases

    /** The Play connection: client, availability, reconnect backoff and waiting requests. */
    private val connection =
        BillingConnection(
            scope = ioScope,
            createBillingClient = createBillingClient,
            purchasesListener = this,
            stateListener = this,
            actor = connectionActor,
        )

    /** The billing client, once created. Setting `null` makes the next connection create a new one. */
    var billingClient: BillingClient?
        get() = connection.client
        set(value) = connection.setClient(value)

    /**
     * Whether Play Billing can be used on this device. Once [BillingAvailability.Unavailable],
     * requests fail straight away instead of reconnecting, until the app next returns to
     * the foreground and billing is probed again.
     */
    internal val availability: StateFlow<BillingAvailability> = connection.availability

    // Setup mutable state flow for purchase results
    override val purchaseResults = MutableStateFlow<InternalPurchaseResult?>(null)

    init {
        startConnectionOnMainThread()
        // Unavailable isn't always permanent - the user may sign in to the Play Store
        // while the app is in the background - so probe again on every foregrounding.
        ioScope.launch {
            appLifecycleObserver.isInBackground
                .drop(1)
                .filter { inBackground -> !inBackground }
                .collect { connection.reprobe() }
        }
    }

    internal class Handler(
        val scope: CoroutineScope,
    ) {
        fun post(action: () -> Unit) {
            scope.launch {
                action()
            }
        }
    }

    override suspend fun queryAllPurchases(): List<Purchase> {
        if (availability.value is BillingAvailability.Unavailable) return emptyList()
        return coroutineScope {
            val apps =
                async { retryOrNull(QUERY_PURCHASES_MAX_RETRIES) { queryType(ProductType.INAPP).getOrThrow() } }
            val subs =
                async { retryOrNull(QUERY_PURCHASES_MAX_RETRIES) { queryType(ProductType.SUBS).getOrThrow() } }
            (apps.await() ?: emptyList()) + (subs.await() ?: emptyList())
        }
    }

    // Unlike the other queries, this one reconnects even when billing was marked unavailable:
    // a failure here blocks a purchase, so it's worth asking Play again.
    override suspend fun querySubscriptionPurchases(): Result<List<Purchase>> {
        val purchases =
            retryOrNull(QUERY_PURCHASES_MAX_RETRIES) {
                awaitConnection().getOrThrow()
                queryType(ProductType.SUBS).getOrThrow()
            }
        return purchases?.let { Result.success(it) }
            ?: Result.failure(BillingError.UnknownError)
    }

    private suspend fun awaitConnection(): Result<Unit> = connection.awaitConnection(CONNECT_TIMEOUT_MS)

    override suspend fun consume(purchaseToken: String): Result<String> =
        suspendCoroutine { cont ->
            val useCase =
                ConsumePurchaseUseCase(
                    ConsumePurchaseUseCaseParams(
                        purchaseToken = purchaseToken,
                        appInBackground = appLifecycleObserver.isInBackground.value,
                    ),
                    onReceive = { consumedToken ->
                        cont.resume(Result.success(consumedToken))
                    },
                    onError = { error ->
                        cont.resume(Result.failure(error))
                    },
                    withConnectedClient = ::withConnectedClient,
                    executeRequestOnUIThread = ::executeRequestOnUIThread,
                )
            useCase.run()
        }

    fun startConnectionOnMainThread(delayMilliseconds: Long = 0) {
        connection.connect(delayMilliseconds)
    }

    fun startConnection() {
        connection.connect()
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
    override suspend fun awaitGetProducts(fullProductIds: Set<String>): Set<StoreProduct> {
        val (cachedProducts, missingFullProductIds) =
            when (val lookup = productDetails.lookup(fullProductIds)) {
                is ProductLookup.AllCached -> return lookup.products
                is ProductLookup.KnownMissing -> throw lookup.error
                is ProductLookup.Partial -> lookup.cached to lookup.missingIds
            }

        return try {
            withTimeout(PRODUCTS_QUERY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    getProducts(
                        missingFullProductIds,
                        object : GetStoreProductsCallback {
                            override fun onReceived(storeProducts: Set<StoreProduct>) {
                                productDetails.store(missingFullProductIds, storeProducts)

                                // Combine cached products (now including the newly fetched ones) with the fetched products
                                val allProducts = cachedProducts + storeProducts
                                // A late answer to a request that already timed out is ignored here,
                                // but the products it carried are now cached for the next request.
                                if (continuation.isActive) continuation.resume(allProducts)
                            }

                            override fun onError(error: BillingError) {
                                // Billing errors aren't cached so a later request can retry. Setup-time
                                // unavailability is remembered in [availability]; a BillingNotAvailable
                                // returned by a query isn't, so the next load queries billing again.
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        },
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            val message =
                "Timed out after ${PRODUCTS_QUERY_TIMEOUT_MS}ms waiting for Google Play to return " +
                    "products: ${missingFullProductIds.joinToString()}"
            Logger.debug(LogLevel.error, LogScope.productsManager, message)
            throw BillingError.Timeout(message)
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
        if (types.isEmpty()) {
            callback.onReceived(collectedStoreProducts)
            return
        }

        // Each type resolves exactly once, so onReceived can only fire once every type
        // has succeeded; the flag keeps onError to a single delivery when several types fail.
        val collected = ConcurrentLinkedQueue<StoreProduct>()
        val typesRemaining = AtomicInteger(types.size)
        val errored = AtomicBoolean(false)

        types.forEach { type ->
            queryProductDetailsAsync(
                productType = type,
                subscriptionIds = subscriptionIds,
                decomposedProductIdsBySubscriptionId = decomposedProductIdsBySubscriptionId,
                onReceive = { storeProducts ->
                    dispatch {
                        collected.addAll(storeProducts)
                        if (typesRemaining.decrementAndGet() == 0) {
                            callback.onReceived(collectedStoreProducts + collected)
                        }
                    }
                },
                onError = { error ->
                    dispatch {
                        if (errored.compareAndSet(false, true)) {
                            callback.onError(error)
                        }
                    }
                },
            )
        }
    }

    private fun dispatch(action: () -> Unit) {
        threadHandler.post(action)
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

    private fun executeRequestOnUIThread(
        delayMilliseconds: Long? = null,
        request: (BillingError?) -> Unit,
    ) {
        connection.request(delayMilliseconds, request)
    }

    override fun onBillingServiceDisconnected() {
        connection.disconnected()
    }

    /**
     * Fetches the Play Store's [com.android.billingclient.api.BillingConfig] to learn the
     * country of the user's Play Store account. Waits for the billing client to connect
     * if it hasn't yet; resolves to `null` if billing is unavailable or the fetch fails.
     */
    override suspend fun getStorefrontCountryCode(): String? {
        val result = CompletableDeferred<String?>(parent = currentCoroutineContext()[Job])

        try {
            executeRequestOnUIThread { connectionError ->
                if (connectionError != null) {
                    result.complete(null)
                    return@executeRequestOnUIThread
                }
                val dispatched =
                    withConnectedClient {
                        getBillingConfigAsync(
                            GetBillingConfigParams.newBuilder().build(),
                        ) { billingResult, billingConfig ->
                            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && billingConfig != null) {
                                result.complete(billingConfig.countryCode)
                            } else {
                                Logger.debug(
                                    LogLevel.debug,
                                    LogScope.productsManager,
                                    "Failed to fetch billing config: ${billingResult.debugMessage} " +
                                        "ErrorCode: ${billingResult.responseCode}",
                                )
                                result.complete(null)
                            }
                        }
                    }
                if (dispatched == null) {
                    result.complete(null)
                }
            }

            return result.await()
        } finally {
            result.cancel()
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        connection.setupFinished(billingResult)
    }

    fun withConnectedClient(receivingFunction: BillingClient.() -> Unit): Unit? {
        return billingClient?.takeIf { it.isReady }?.receivingFunction() ?: run {
            Logger.debug(
                LogLevel.error,
                LogScope.productsManager,
                "Billing client not ready",
            )
            return@run null
        }
    }

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        Logger.debug(
            LogLevel.debug,
            LogScope.billing,
            "onPurchasesUpdated: $result",
        )
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                Logger.debug(
                    LogLevel.debug,
                    LogScope.billing,
                    "Purchase: $purchase",
                )
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

            Logger.debug(
                LogLevel.debug,
                LogScope.billing,
                "User cancelled purchase",
            )
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                purchaseResults.emit(InternalPurchaseResult.Failed(Exception(result.responseCode.toString())))
            }
            Logger.debug(
                LogLevel.debug,
                LogScope.billing,
                "Purchase failed",
            )
        }
    }

    /**
     * Get the latest transaction from the purchaseResults flow.
     *
     * @param factory StoreTransactionFactory to create the StoreTransaction
     * @return StoreTransaction if the purchase was finished by Superwall, null otherwise
     */
    override suspend fun getLatestTransaction(factory: StoreTransactionFactory): StoreTransaction? {
        // Get the latest from purchaseResults
        purchaseResults
            .asStateFlow()
            .filter { it != null }
            .first()
            .let { purchaseResult ->
                return when (purchaseResult) {
                    is InternalPurchaseResult.Purchased -> {
                        if (shouldFinishTransactions) {
                            return factory.makeStoreTransaction(purchaseResult.purchase)
                        } else {
                            null
                        }
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

    internal suspend fun latestPurchase() =
        queryAllPurchases()
            .sortedByDescending {
                it.purchaseTime
            }.map {
                val product = awaitGetProducts(it.products.toSet()).first()
                val millisSincePurchase = (System.currentTimeMillis() - it.purchaseTime)
                val subPeriod = product.subscriptionPeriod?.toMillis() ?: 0
                val isGracePeriod =
                    it.purchaseState == Purchase.PurchaseState.PENDING &&
                        it.isAutoRenewing
                val isExpired =
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        !it.isAutoRenewing &&
                        millisSincePurchase > subPeriod
                val isSubscribed =
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        millisSincePurchase < subPeriod

                mapOf(
                    "latestSubscriptionPeriodType" to product.subscriptionPeriod?.unit,
                    "latestSubscriptionState" to it.purchaseState,
                    "latestSubscriptionWillAutoRenew" to it.isAutoRenewing,
                )
            }

    suspend fun queryType(type: String): Result<List<Purchase>> {
        val deferred = CompletableDeferred<Result<List<Purchase>>>()
        val params =
            QueryPurchasesParams
                .newBuilder()
                .setProductType(type)
                .build()

        withConnectedClient {
            queryPurchasesAsync(params) { billingResult, purchasesList ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.nativePurchaseController,
                        message = "Unable to query for purchases.",
                    )
                    deferred.complete(Result.failure(Throwable("Billing query failed with code ${billingResult.responseCode}")))
                    return@queryPurchasesAsync
                }

                deferred.complete(Result.success(purchasesList))
            }
        } ?: deferred.complete(
            Result.failure(Throwable("Billing client not ready")),
        )

        return withTimeoutOrNull(QUERY_PURCHASES_TIMEOUT_MS) {
            deferred.await()
        } ?: Result.failure(Throwable("Query purchases timed out"))
    }
}

fun Pair<BillingResult, List<Purchase>?>.toInternalResult(): List<InternalPurchaseResult> {
    val (result, purchases) = this
    return if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
        purchases.map {
            InternalPurchaseResult.Purchased(it)
        }
    } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
        listOf(InternalPurchaseResult.Cancelled)
    } else {
        listOf(InternalPurchaseResult.Failed(Exception(result.responseCode.toString())))
    }
}
