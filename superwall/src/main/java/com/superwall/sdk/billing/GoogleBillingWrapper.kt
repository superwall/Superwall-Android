package com.superwall.sdk.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.superwall.sdk.billing.google.GooglePlayStore
import com.superwall.sdk.billing.google.PlayPurchases
import com.superwall.sdk.billing.google.purchaseResultOf
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
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

// Purchase outcomes kept for slow collectors of the wrapper's neutral purchase updates.
private const val PURCHASE_UPDATES_BUFFER = 64

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

    // Kept for binary compatibility; the query constants now live in [PurchaseQueries].
    companion object

    /** Forgets every product this wrapper fetched, so the next request asks Play again. */
    @androidx.annotation.VisibleForTesting
    internal fun clearProductsCache() {
        productDetails.clear()
    }

    interface Factory :
        HasExternalPurchaseControllerFactory,
        HasInternalPurchaseControllerFactory,
        OptionsFactory

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

    /** The last Play purchase this wrapper saw for each purchase token. */
    internal val playPurchases = PlayPurchases()

    // Every outcome Play reports to [onPurchasesUpdated], in neutral form.
    private val purchaseUpdates = MutableSharedFlow<StorePurchaseResult>(extraBufferCapacity = PURCHASE_UPDATES_BUFFER)

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

    /** Products, purchases, consume and storefront queries over [connection]. */
    private val queries =
        PurchaseQueries(
            connection = connection,
            productDetails = productDetails,
            appInBackground = { appLifecycleObserver.isInBackground.value },
        )

    /** This wrapper as the SDK's store-neutral [Store]. */
    internal val store: Store =
        GooglePlayStore(
            queries = queries,
            availability = availability,
            purchaseUpdates = purchaseUpdates,
            purchases = playPurchases,
        )

    override suspend fun queryAllPurchases(): List<Purchase> = queries.allPurchases()

    override suspend fun querySubscriptionPurchases(): Result<List<Purchase>> = queries.subscriptionPurchases()

    override suspend fun consume(purchaseToken: String): Result<String> = queries.consume(purchaseToken)

    fun startConnectionOnMainThread(delayMilliseconds: Long = 0) {
        connection.connect(delayMilliseconds)
    }

    fun startConnection() {
        connection.connect()
    }

    /**
     * Gets the StoreProduct(s) for the given list of product ids for all types.
     *
     * @param [fullProductIds] List of productIds
     *
     * @throws [BillingError] if there's an error retrieving the products.
     * @return A list of [StoreProduct] with the products that have been able to be fetched from the store successfully.
     * Not found products will be ignored.
     */
    @JvmSynthetic
    @Throws(Throwable::class)
    override suspend fun awaitGetProducts(fullProductIds: Set<String>): Set<StoreProduct> = queries.products(fullProductIds)

    override fun onBillingServiceDisconnected() {
        connection.disconnected()
    }

    /**
     * Fetches the Play Store's [com.android.billingclient.api.BillingConfig] to learn the
     * country of the user's Play Store account. Waits for the billing client to connect
     * if it hasn't yet; resolves to `null` if billing is unavailable or the fetch fails.
     */
    override suspend fun getStorefrontCountryCode(): String? = queries.storefrontCountryCode()

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        connection.setupFinished(billingResult)
    }

    fun withConnectedClient(receivingFunction: BillingClient.() -> Unit): Unit? = queries.withConnectedClient(receivingFunction)


    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        Logger.debug(
            LogLevel.debug,
            LogScope.billing,
            "onPurchasesUpdated: $result",
        )
        purchases?.let { playPurchases.record(it) }
        purchaseUpdates.tryEmit(purchaseResultOf(result, purchases))
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

    suspend fun queryType(type: String): Result<List<Purchase>> = queries.queryType(type)
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
