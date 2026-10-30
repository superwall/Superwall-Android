package com.superwall.sdk.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.retryOrNull
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Play queries the SDK makes, as suspend functions over a [BillingConnection]: products,
 * purchases, consume and the storefront country. Each waits for the connection the same way
 * the old callback code did, so retries, backoff and errors behave as before.
 */
internal class PurchaseQueries(
    private val connection: BillingConnection,
    private val productDetails: ProductDetailsRepository,
    private val appInBackground: () -> Boolean,
) {
    internal companion object {
        const val QUERY_PURCHASES_TIMEOUT_MS = 10_000L
        const val QUERY_PURCHASES_MAX_RETRIES = 3
        const val CONNECT_TIMEOUT_MS = 5_000L
    }

    /** Runs [receivingFunction] on the client when it is connected; `null` when it isn't. */
    fun withConnectedClient(receivingFunction: BillingClient.() -> Unit): Unit? =
        connection.client?.takeIf { it.isReady }?.receivingFunction() ?: run {
            Logger.debug(LogLevel.error, LogScope.productsManager, "Billing client not ready")
            null
        }

    private fun executeRequest(
        delayMilliseconds: Long? = null,
        request: (BillingError?) -> Unit,
    ) = connection.request(delayMilliseconds, request)

    suspend fun awaitConnection(): Result<Unit> = connection.awaitConnection(CONNECT_TIMEOUT_MS)

    /**
     * Returns the products for [fullProductIds], from the cache where it can. Products Play
     * didn't return are left out, never failed; recently missing ones aren't asked for again.
     * Throws [BillingError.Timeout] when Play
     * doesn't answer within [PRODUCTS_QUERY_TIMEOUT_MS].
     */
    suspend fun products(fullProductIds: Set<String>): Set<StoreProduct> {
        val (cached, missingIds) =
            when (val lookup = productDetails.lookup(fullProductIds)) {
                is ProductLookup.AllCached -> return lookup.products
                is ProductLookup.Partial -> lookup.cached to lookup.missingIds
            }

        // The fetch is driven by Play's callbacks, not by a coroutine, so a request that times
        // out leaves nothing suspended behind it. An answer that arrives after the timeout still
        // fills the cache for the next request.
        val fetch = fetchProducts(missingIds)
        val fetched =
            try {
                withTimeout(PRODUCTS_QUERY_TIMEOUT_MS) { fetch.await() }
            } catch (e: TimeoutCancellationException) {
                val message =
                    "Timed out after ${PRODUCTS_QUERY_TIMEOUT_MS}ms waiting for Google Play to return " +
                        "products: ${missingIds.joinToString()}"
                Logger.debug(LogLevel.error, LogScope.productsManager, message)
                throw BillingError.Timeout(message)
            }
        // Billing errors aren't cached, so a later request can retry them.
        return cached + fetched
    }

    /**
     * The products for [fullProductIds], with the ids Play didn't return listed as missing
     * instead of failing. Ids already known to be missing aren't asked for again.
     */
    suspend fun productsResult(fullProductIds: Set<String>): ProductsResult {
        val queryable = fullProductIds - productDetails.knownMissing(fullProductIds)
        val found = if (queryable.isEmpty()) emptySet() else products(queryable)
        val foundIds = found.map { it.fullIdentifier }.toSet()
        return ProductsResult(found = found, missingIds = fullProductIds - foundIds)
    }

    /**
     * Queries subscriptions and one-time products together; the first failure fails both. Each
     * type's answer completes its own deferred from Play's callback; once both have answered, the
     * products are cached and the returned deferred completes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fetchProducts(fullProductIds: Set<String>): CompletableDeferred<Set<StoreProduct>> {
        val decomposed = fullProductIds.map(DecomposedProductIds::from)
        val bySubscriptionId: MutableMap<String, MutableList<DecomposedProductIds>> =
            decomposed.groupByTo(mutableMapOf()) { it.subscriptionId }
        val subscriptionIds = bySubscriptionId.keys.toSet()

        val result = CompletableDeferred<Set<StoreProduct>>()
        val byType = listOf(ProductType.SUBS, ProductType.INAPP).associateWith { CompletableDeferred<List<StoreProduct>>() }

        // Runs after each type answers. Both may see the other complete and run it twice; storing
        // is idempotent and the second complete() is a no-op. It runs inside Play's listener, so
        // it must never throw: once a type failed, the request has already failed and a later
        // answer from the other type is dropped.
        fun finishWhenAllAnswered() {
            if (result.isCompleted) return
            if (byType.values.any { !it.isCompleted || it.getCompletionExceptionOrNull() != null }) return
            val products = byType.values.flatMap { it.getCompleted() }.toSet()
            productDetails.store(fullProductIds, products)
            result.complete(products)
        }

        byType.forEach { (type, answer) ->
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.productsManager,
                message = "Requesting products from the store with identifiers: ${subscriptionIds.joinToString()}",
            )
            QueryProductDetailsUseCase(
                QueryProductDetailsUseCaseParams(
                    subscriptionIds = subscriptionIds,
                    decomposedProductIdsBySubscriptionId = bySubscriptionId,
                    productType = type,
                    appInBackground = appInBackground(),
                ),
                onReceive = { products ->
                    if (answer.complete(products)) finishWhenAllAnswered()
                },
                onError = { error ->
                    answer.completeExceptionally(error)
                    result.completeExceptionally(error)
                },
                withConnectedClient = ::withConnectedClient,
                executeRequestOnUIThread = ::executeRequest,
            ).run()
        }
        return result
    }

    suspend fun consume(purchaseToken: String): Result<String> =
        suspendCancellableCoroutine { continuation ->
            ConsumePurchaseUseCase(
                ConsumePurchaseUseCaseParams(
                    purchaseToken = purchaseToken,
                    appInBackground = appInBackground(),
                ),
                onReceive = { token -> if (continuation.isActive) continuation.resume(Result.success(token)) },
                onError = { error -> if (continuation.isActive) continuation.resume(Result.failure(error)) },
                withConnectedClient = ::withConnectedClient,
                executeRequestOnUIThread = ::executeRequest,
            ).run()
        }

    /** Purchases of [type] the client knows of. Doesn't connect: fails when the client isn't ready. */
    suspend fun queryType(type: String): Result<List<Purchase>> {
        val deferred = CompletableDeferred<Result<List<Purchase>>>()
        val params = QueryPurchasesParams.newBuilder().setProductType(type).build()

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
        } ?: deferred.complete(Result.failure(Throwable("Billing client not ready")))

        return withTimeoutOrNull(QUERY_PURCHASES_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(Throwable("Query purchases timed out"))
    }

    /** One-time and subscription purchases; empty when billing is unavailable. */
    suspend fun allPurchases(): List<Purchase> {
        if (connection.availability.value is BillingAvailability.Unavailable) return emptyList()
        return coroutineScope {
            val apps = async { retryOrNull(QUERY_PURCHASES_MAX_RETRIES) { queryType(ProductType.INAPP).getOrThrow() } }
            val subs = async { retryOrNull(QUERY_PURCHASES_MAX_RETRIES) { queryType(ProductType.SUBS).getOrThrow() } }
            (apps.await() ?: emptyList()) + (subs.await() ?: emptyList())
        }
    }

    /**
     * One-time and subscription purchases. Unlike [allPurchases], a query that can't be made
     * fails instead of counting as "no purchases".
     */
    suspend fun activePurchases(): Result<List<Purchase>> {
        (connection.availability.value as? BillingAvailability.Unavailable)?.let { return Result.failure(it.error) }
        return coroutineScope {
            val apps = async { retryOrNull(QUERY_PURCHASES_MAX_RETRIES) { queryType(ProductType.INAPP).getOrThrow() } }
            val subs = async { retryOrNull(QUERY_PURCHASES_MAX_RETRIES) { queryType(ProductType.SUBS).getOrThrow() } }
            val inApp = apps.await()
            val subscriptions = subs.await()
            if (inApp == null || subscriptions == null) {
                Result.failure(BillingError.UnknownError)
            } else {
                Result.success(inApp + subscriptions)
            }
        }
    }

    /**
     * Launches Play's purchase flow on [activity] once connected, on [mainDispatcher] as Play
     * requires. Succeeds when the flow opened; its outcome reaches the purchases listener.
     */
    suspend fun launchBillingFlow(
        activity: Activity,
        params: BillingFlowParams,
        mainDispatcher: CoroutineDispatcher,
    ): Result<Unit> {
        awaitConnection().onFailure { return Result.failure(it) }
        val client =
            connection.client?.takeIf { it.isReady }
                ?: return Result.failure(Throwable("Billing client not ready"))
        val result = withContext(mainDispatcher) { client.launchBillingFlow(activity, params) }
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(Unit)
        } else {
            Result.failure(Exception(result.responseCode.toString()))
        }
    }

    /** Acknowledges the purchase with [purchaseToken], waiting for the connection first. */
    suspend fun acknowledge(purchaseToken: String): Result<Unit> {
        awaitConnection().onFailure { return Result.failure(it) }
        val result = CompletableDeferred<Result<Unit>>()
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        withConnectedClient {
            acknowledgePurchase(params) { billingResult ->
                result.complete(
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Throwable("Acknowledge failed with code ${billingResult.responseCode}"))
                    },
                )
            }
        } ?: result.complete(Result.failure(Throwable("Billing client not ready")))
        return withTimeoutOrNull(QUERY_PURCHASES_TIMEOUT_MS) { result.await() }
            ?: Result.failure(Throwable("Acknowledge purchase timed out"))
    }

    // Unlike the other queries, this one reconnects even when billing was marked unavailable:
    // a failure here blocks a purchase, so it's worth asking Play again.
    suspend fun subscriptionPurchases(): Result<List<Purchase>> {
        val purchases =
            retryOrNull(QUERY_PURCHASES_MAX_RETRIES) {
                awaitConnection().getOrThrow()
                queryType(ProductType.SUBS).getOrThrow()
            }
        return purchases?.let { Result.success(it) } ?: Result.failure(BillingError.UnknownError)
    }

    /**
     * The country of the user's Play Store account, from Play's billing config. Waits for the
     * connection; `null` when billing is unavailable or the fetch fails.
     */
    suspend fun storefrontCountryCode(): String? {
        val result = CompletableDeferred<String?>(parent = currentCoroutineContext()[Job])
        try {
            executeRequest { connectionError ->
                if (connectionError != null) {
                    result.complete(null)
                    return@executeRequest
                }
                val dispatched =
                    withConnectedClient {
                        getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { billingResult, billingConfig ->
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
                if (dispatched == null) result.complete(null)
            }
            return result.await()
        } finally {
            result.cancel()
        }
    }
}
