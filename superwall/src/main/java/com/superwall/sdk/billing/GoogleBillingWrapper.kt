package com.superwall.sdk.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

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

// How long a product id that Play didn't return is skipped before we ask for it again.
// Short enough that a product going live is picked up quickly, long enough that a
// paywall referencing a stale id doesn't re-query it on every load.
internal const val MISSING_PRODUCT_TTL_MS = 5L * 60L * 1000L

class GoogleBillingWrapper(
    val context: Context,
    val ioScope: IOScope,
    val appLifecycleObserver: AppLifecycleObserver,
    val factory: Factory,
    val createBillingClient: (PurchasesUpdatedListener) -> BillingClient = {
        BillingClient
            .newBuilder(context)
            .setListener(it)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            ).build()
    },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : PurchasesUpdatedListener,
    BillingClientStateListener,
    Billing {
    companion object {
        private const val QUERY_PURCHASES_TIMEOUT_MS = 10_000L
        private const val QUERY_PURCHASES_MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }

    // Products Play returned, by full identifier.
    private val productsCache = ConcurrentHashMap<String, StoreProduct>()

    // Ids Play didn't return, with the time we found out. Skipped (not failed) until
    // [MISSING_PRODUCT_TTL_MS] has passed so a missing id never breaks the other ids' load.
    private val recentlyMissing = ConcurrentHashMap<String, Long>()

    override fun clearProductsCache() {
        productsCache.clear()
        recentlyMissing.clear()
    }

    interface Factory :
        HasExternalPurchaseControllerFactory,
        HasInternalPurchaseControllerFactory,
        OptionsFactory

    private val threadHandler = Handler(ioScope)
    private val shouldFinishTransactions: Boolean
        get() = !factory.makeSuperwallOptions().shouldObservePurchases

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

    private val transientSetupFailures = AtomicInteger(0)

    private val _availability = MutableStateFlow<BillingAvailability>(BillingAvailability.Unknown)

    /**
     * Whether Play Billing can be used on this device. Once [BillingAvailability.Unavailable],
     * requests fail straight away instead of reconnecting, until the app next returns to
     * the foreground and billing is probed again.
     */
    internal val availability = _availability.asStateFlow()

    // Setup mutable state flow for purchase results
    override val purchaseResults = MutableStateFlow<InternalPurchaseResult?>(null)

    // The billing flow launched through [launchBillingFlow] that is waiting for Play's answer.
    private val inFlightPurchaseFlow = AtomicReference<CompletableDeferred<PurchaseFlowResult>?>(null)

    internal val IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE =
        "Google Play In-app Billing API version is less than 3"

    init {
        startConnectionOnMainThread()
        // Unavailable isn't always permanent - the user may sign in to the Play Store
        // while the app is in the background - so probe again on every foregrounding.
        ioScope.launch {
            appLifecycleObserver.isInBackground
                .drop(1)
                .filter { inBackground -> !inBackground }
                .collect {
                    if (availability.value is BillingAvailability.Unavailable) {
                        _availability.value = BillingAvailability.Unknown
                        transientSetupFailures.set(0)
                        startConnection()
                    }
                }
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

        fun postDelayed(
            action: () -> Unit,
            delayMilliseconds: Long,
        ) {
            scope.launch {
                delay(delayMilliseconds)
                action()
            }
        }
    }

    private fun executePendingRequests() {
        synchronized(this@GoogleBillingWrapper) {
            while (billingClient?.isReady == true) {
                serviceRequests.poll()?.let { (request, delayMilliseconds) ->
                    if (delayMilliseconds != null) {
                        threadHandler.postDelayed(
                            { request(null) },
                            delayMilliseconds,
                        )
                    } else {
                        threadHandler.post { request(null) }
                    }
                } ?: break
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

    override suspend fun queryPurchasesOfType(productType: String): Result<List<Purchase>> {
        awaitConnection().onFailure { return Result.failure(it) }
        return queryType(productType)
    }

    override suspend fun launchBillingFlow(
        activity: Activity,
        params: BillingFlowParams,
    ): PurchaseFlowResult {
        awaitConnection().onFailure {
            return PurchaseFlowResult(
                billingResult(
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                    "Billing isn't connected: ${it.message}",
                ),
            )
        }

        val flow = CompletableDeferred<PurchaseFlowResult>()
        if (!inFlightPurchaseFlow.compareAndSet(null, flow)) {
            return PurchaseFlowResult(
                billingResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "A purchase is already in progress"),
            )
        }
        try {
            val launched =
                billingClient?.takeIf { it.isReady }?.launchBillingFlow(activity, params)
                    ?: billingResult(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "Billing client not ready")
            // A flow that didn't start won't get a purchase update, so answer with the launch result.
            if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
                return PurchaseFlowResult(launched)
            }
            return flow.await()
        } finally {
            inFlightPurchaseFlow.compareAndSet(flow, null)
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Result<Unit> {
        awaitConnection().onFailure { return Result.failure(it) }
        val deferred = CompletableDeferred<Result<Unit>>()
        val params =
            AcknowledgePurchaseParams
                .newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
        withConnectedClient {
            acknowledgePurchase(params) { result ->
                deferred.complete(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Throwable("Acknowledge failed with code ${result.responseCode}"))
                    },
                )
            }
        } ?: return Result.failure(Throwable("Billing client not ready"))

        return withTimeoutOrNull(QUERY_PURCHASES_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(Throwable("Acknowledge purchase timed out"))
    }

    private fun billingResult(
        code: Int,
        message: String,
    ): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage(message)
            .build()

    private suspend fun awaitConnection(): Result<Unit> {
        if (billingClient?.isReady == true) return Result.success(Unit)
        val connected = CompletableDeferred<BillingError?>()
        synchronized(this) {
            serviceRequests.add(
                { error: BillingError? ->
                    connected.complete(error)
                    Unit
                } to null,
            )
        }
        startConnectionOnMainThread()
        val error =
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: return Result.failure(Throwable("Billing connection timed out"))
        return error?.let { Result.failure(it) } ?: Result.success(Unit)
    }

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
        threadHandler.postDelayed(
            { startConnection() },
            delayMilliseconds,
        )
    }

    fun startConnection() {
        synchronized(this@GoogleBillingWrapper) {
            if (billingClient == null) {
                billingClient =
                    try {
                        createBillingClient(this)
                    } catch (e: Throwable) {
                        markUnavailable(
                            BillingError.BillingNotAvailable(
                                "Billing is not available in this device. " +
                                    "The billing client could not be created: ${e.message}",
                            ),
                        )
                        return
                    }
            }

            reconnectionAlreadyScheduled = false

            billingClient?.let {
                // A second startConnection while one is in flight is answered with
                // DEVELOPER_ERROR and does nothing; the in-flight one still calls back.
                if (it.connectionState == BillingClient.ConnectionState.CONNECTING) {
                    return
                }
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
     * @throws [BillingError] if Play Billing can't be queried. A product that Play doesn't know
     * about is never an error: it is left out of the result.
     * @return A list of [StoreProduct] with the products that have been able to be fetched from the store successfully.
     * Not found products will be ignored.
     */
    @JvmSynthetic
    @Throws(Throwable::class)
    override suspend fun awaitGetProducts(fullProductIds: Set<String>): Set<StoreProduct> {
        val now = currentTimeMillis()
        val cachedProducts = fullProductIds.mapNotNull { productsCache[it] }.toSet()
        val cachedIds = cachedProducts.map { it.fullIdentifier }.toSet()
        val skippedIds =
            fullProductIds.filter { id ->
                val missingSince = recentlyMissing[id] ?: return@filter false
                val stillFresh = now - missingSince < MISSING_PRODUCT_TTL_MS
                if (!stillFresh) recentlyMissing.remove(id, missingSince)
                stillFresh
            }

        val missingFullProductIds = fullProductIds - cachedIds - skippedIds.toSet()
        if (missingFullProductIds.isEmpty()) {
            return cachedProducts
        }

        return try {
            withTimeout(PRODUCTS_QUERY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    getProducts(
                        missingFullProductIds,
                        object : GetStoreProductsCallback {
                            override fun onReceived(storeProducts: Set<StoreProduct>) {
                                storeProducts.forEach { product ->
                                    productsCache[product.fullIdentifier] = product
                                    recentlyMissing.remove(product.fullIdentifier)
                                }
                                val foundProductIds = storeProducts.map { it.fullIdentifier }.toSet()
                                val missedAt = currentTimeMillis()
                                (missingFullProductIds - foundProductIds).forEach { fullProductId ->
                                    recentlyMissing[fullProductId] = missedAt
                                }

                                // A late answer to a request that already timed out is ignored here,
                                // but the products it carried are now cached for the next request.
                                if (continuation.isActive) continuation.resume(cachedProducts + storeProducts)
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

    @Synchronized
    private fun executeRequestOnUIThread(
        delayMilliseconds: Long? = null,
        request: (BillingError?) -> Unit,
    ) {
        (availability.value as? BillingAvailability.Unavailable)?.let { unavailable ->
            threadHandler.post { request(unavailable.error) }
            return
        }
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
        // Requests already on the queue only run from a setup callback, and a request that
        // finds the client disconnected re-queues itself, so reconnect for either to finish.
        if (serviceRequests.isNotEmpty()) {
            retryBillingServiceConnectionWithExponentialBackoff()
        }
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
        threadHandler.post {
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.productsManager,
                        "Billing client connected",
                    )
                    _availability.value = BillingAvailability.Available
                    executePendingRequests()
                    reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
                    transientSetupFailures.set(0)
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
                        error.message
                            ?: "Billing is not available in this device. ${billingResult.debugMessage}",
                    )
                    // The calls will fail with an error that will be surfaced. We want to surface these errors
                    // Can't call executePendingRequests because it will not do anything since it checks for isReady()
                    markUnavailable(error)
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
                    val failures = transientSetupFailures.incrementAndGet()
                    if (failures >= MAX_TRANSIENT_SETUP_FAILURES &&
                        availability.value !is BillingAvailability.Unavailable
                    ) {
                        markUnavailable(
                            BillingError.BillingNotAvailable(
                                "Billing is not available in this device. Setup failed $failures " +
                                    "times in a row. Last error: ${billingResult.debugMessage} " +
                                    "ErrorCode: ${billingResult.responseCode}.",
                            ),
                        )
                    }
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
                    // Reported when startConnection is called while a connection is already in
                    // flight. That connection delivers its own result, so there's nothing to do
                    // while it's still connecting (or already connected). If it has since
                    // dropped, no callback is coming for the queued requests, so reconnect.
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, developer error: ${billingResult.responseCode}",
                    )
                    val connectionState = billingClient?.connectionState
                    if (connectionState != BillingClient.ConnectionState.CONNECTING &&
                        connectionState != BillingClient.ConnectionState.CONNECTED &&
                        serviceRequests.isNotEmpty()
                    ) {
                        retryBillingServiceConnectionWithExponentialBackoff()
                    }
                }
            }
        }
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
            LogScope.storeKitManager,
            "onPurchasesUpdated: $result",
        )
        // Answer the flow we launched, if any. Play also reports purchases from flows we didn't
        // launch (observer mode, pending purchases settling), which only go to [purchaseResults].
        inFlightPurchaseFlow.getAndSet(null)?.complete(PurchaseFlowResult(result, purchases.orEmpty()))
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                Logger.debug(
                    LogLevel.debug,
                    LogScope.storeKitManager,
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
                LogScope.storeKitManager,
                "User cancelled purchase",
            )
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                purchaseResults.emit(InternalPurchaseResult.Failed(Exception(result.responseCode.toString())))
            }
            Logger.debug(
                LogLevel.debug,
                LogScope.storeKitManager,
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

    private fun markUnavailable(error: BillingError.BillingNotAvailable) {
        _availability.value = BillingAvailability.Unavailable(error)
        sendErrorsToAllPendingRequests(error)
    }

    @Synchronized
    private fun sendErrorsToAllPendingRequests(error: BillingError) {
        while (true) {
            serviceRequests.poll()?.let { (serviceRequest, _) ->
                threadHandler.post {
                    serviceRequest(error)
                }
            } ?: break
        }
    }

    private fun trackProductDetailsNotSupportedIfNeeded() {
        val billingResult =
            billingClient?.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
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
