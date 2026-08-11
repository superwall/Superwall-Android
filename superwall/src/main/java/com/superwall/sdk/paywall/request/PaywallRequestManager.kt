package com.superwall.sdk.paywall.request

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.dependencies.ConfigManagerFactory
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.map
import com.superwall.sdk.misc.mapError
import com.superwall.sdk.misc.onError
import com.superwall.sdk.misc.then
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.request.ProductOverride
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PaywallRequestManagerDepFactory :
    DeviceInfoFactory,
    ConfigManagerFactory {
    fun activePaywallId(): String?

    fun currentCustomerInfo(): CustomerInfo
}

class PaywallRequestManager(
    private val storeManager: StoreManager,
    private val network: Network,
    private val factory: PaywallRequestManagerDepFactory,
    private val ioScope: IOScope,
    private val track: suspend (TrackableSuperwallEvent) -> Unit = {
        Superwall.instance.track(it)
    },
    private val getGlobalOverrides: () -> Map<String, String> = {
        Superwall.instance.overrideProductsByName
    },
) {
    // getPaywall runs on the multi-threaded IO dispatcher, so request dedup relies on
    // ConcurrentHashMap's atomic putIfAbsent/remove(key, value) — not on a single thread.

    private val activeTasks = ConcurrentHashMap<String, Deferred<Paywall>>()
    private val paywallsByHash = ConcurrentHashMap<String, Paywall>()

    suspend fun getPaywall(
        request: PaywallRequest,
        isPreloading: Boolean = false,
    ): Either<Paywall, Throwable> =
        withErrorTracking {
            withContext(ioScope.coroutineContext) {
                val deviceInfo = factory.makeDeviceInfo()
                val joinedSubstituteProductIds =
                    request.overrides.products
                        ?.values
                        ?.sortedBy { it.productIdentifier }
                        ?.joinToString(separator = "") { it.productIdentifier }
                val requestHash =
                    PaywallLogic.requestHash(
                        identifier = request.responseIdentifiers.paywallId,
                        event = request.eventData,
                        locale = deviceInfo.locale,
                        joinedSubstituteProductIds = joinedSubstituteProductIds,
                    )

                var paywall: Paywall? = paywallsByHash[requestHash]
                if (paywall != null &&
                    !request.isDebuggerLaunched
                ) {
                    if (!(isPreloading && paywall.identifier == factory.activePaywallId())) {
                        // If products failed to load previously (e.g. billing was unavailable
                        // during preload), retry loading them now.
                        // Synchronize to avoid TOCTOU race: two concurrent requests
                        // both observing failAt != null and triggering duplicate addProducts.
                        val shouldRetry =
                            synchronized(paywall.productsLoadingInfo) {
                                if (paywall.productsLoadingInfo.failAt != null && paywall.productIds.isNotEmpty()) {
                                    paywall.productsLoadingInfo.failAt = null
                                    true
                                } else {
                                    false
                                }
                            }
                        if (shouldRetry) {
                            paywall = addProducts(paywall, request)
                            if (paywall.productsLoadingInfo.failAt == null) {
                                paywallsByHash[requestHash] = paywall
                            }
                        }
                        return@withContext updatePaywall(paywall, request)
                    } else {
                        return@withContext paywall
                    }
                }

                val existingTask = activeTasks[requestHash]
                if (existingTask != null) {
                    try {
                        paywall = existingTask.await()
                        if (!(isPreloading && paywall.identifier == factory.activePaywallId())) {
                            paywall = updatePaywall(paywall, request)
                        }
                        return@withContext paywall
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Clean up cancelled task and continue with new request
                        activeTasks.remove(requestHash, existingTask)
                        // Don't rethrow, let it continue to create a new task
                    }
                }

                // Use suspendCancellableCoroutine to ensure proper cleanup
                paywall =
                    suspendCancellableCoroutine { continuation ->
                        val deferredTask = CompletableDeferred<Paywall>()

                        // Set up cancellation handler to clean up activeTasks.
                        // remove(key, value) so a slot claimed by a concurrent
                        // request is left untouched.
                        continuation.invokeOnCancellation {
                            activeTasks.remove(requestHash, deferredTask)
                            deferredTask.cancel()
                        }

                        // Launch coroutine to handle async operations
                        ioScope.launch {
                            try {
                                // Claim the in-flight slot atomically; if another
                                // coroutine won the race, await its result instead
                                // of fetching the same paywall again.
                                var winner = activeTasks.putIfAbsent(requestHash, deferredTask)
                                while (winner != null) {
                                    try {
                                        continuation.resume(winner.await())
                                        return@launch
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        // Clean up cancelled task and race for the slot again
                                        activeTasks.remove(requestHash, winner)
                                        winner = activeTasks.putIfAbsent(requestHash, deferredTask)
                                    }
                                }

                                val rawPaywallResult = getRawPaywall(request, isPreloading)
                                rawPaywallResult
                                    .then {
                                        val finalPaywall = addProducts(it, request)
                                        saveRequestHash(requestHash, deferredTask, finalPaywall, request.isDebuggerLaunched)

                                        // Complete both the deferred task and the continuation
                                        deferredTask.complete(finalPaywall)
                                        continuation.resume(finalPaywall)
                                    }.onError { error ->
                                        activeTasks.remove(requestHash, deferredTask)
                                        deferredTask.completeExceptionally(error)
                                        continuation.resumeWithException(error)
                                    }
                            } catch (error: Throwable) {
                                activeTasks.remove(requestHash, deferredTask)
                                deferredTask.completeExceptionally(error)
                                continuation.resumeWithException(error)
                            }
                        }
                    }

                // At this point paywall should not be null, but let's handle it safely
                val finalPaywall = paywall ?: throw IllegalStateException("Paywall should not be null")

                return@withContext if (!(isPreloading && finalPaywall.identifier == factory.activePaywallId())) {
                    updatePaywall(finalPaywall, request)
                } else {
                    finalPaywall
                }
            }
        }

    private suspend fun updatePaywall(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            return@withContext paywall.copy(
                experiment = request.responseIdentifiers.experiment,
                presentationSourceType = request.presentationSourceType,
                presentationId = java.util.UUID.randomUUID().toString(),
            )
        }

    private suspend fun saveRequestHash(
        requestHash: String,
        task: Deferred<Paywall>,
        paywall: Paywall,
        isDebuggerLaunched: Boolean,
    ) = withContext(ioScope.coroutineContext) {
        activeTasks.remove(requestHash, task)
        if (!isDebuggerLaunched) {
            paywallsByHash[requestHash] = paywall
        }
    }

    suspend fun getRawPaywall(
        request: PaywallRequest,
        isPreloading: Boolean = false,
    ): Either<Paywall, *> =

        withContext(ioScope.coroutineContext) {
            Logger.debug(
                LogLevel.debug,
                LogScope.all,
                "!!getRawPaywall - ${request.responseIdentifiers.paywallId}",
            )
            trackResponseStarted(event = request.eventData)
            return@withContext getPaywallResponse(request, isPreloading)
                .then {
                    val paywallInfo =
                        it.getInfo(
                            fromEvent = request.eventData,
                        )
                    trackResponseLoaded(
                        paywallInfo,
                        event = request.eventData,
                    )
                }
        }

    private suspend fun getPaywallResponse(
        request: PaywallRequest,
        isPreloading: Boolean = false,
    ): Either<Paywall, *> =
        withContext(ioScope.coroutineContext) {
            val responseLoadStartTime = Date()
            val paywallId = request.responseIdentifiers.paywallId
            val event = request.eventData

            return@withContext (
                factory
                    .makeStaticPaywall(
                        paywallId = paywallId,
                        isDebuggerLaunched = request.isDebuggerLaunched,
                    )?.let {
                        Either.Success<Paywall, Throwable>(it)
                    } ?: network.getPaywall(
                    identifier = paywallId,
                    event = event,
                )
            ).then {
                Logger.debug(
                    LogLevel.debug,
                    LogScope.all,
                    "!!getPaywallResponse - $paywallId - $it",
                )
            }.map {
                if (!(isPreloading && it.identifier == factory.activePaywallId())) {
                    it.experiment = request.responseIdentifiers.experiment
                    it.responseLoadingInfo.startAt = responseLoadStartTime
                    it.responseLoadingInfo.endAt = Date()
                }
                it
            }.then {
                Logger.debug(
                    LogLevel.debug,
                    LogScope.all,
                    "!!getPaywallResponse - $paywallId - $it - ${it.experiment}",
                )
            }.mapError {
                PaywallLogic.handlePaywallError(
                    it,
                    event,
                )
            }
        }

    // MARK: - Analytics
    // Lifecycle events are tracked without awaiting so dispatcher hops and the app's
    // delegate callback never sit on the load path. Payloads are built eagerly at the
    // call site so launched tracks can't observe later paywall mutation.
    private fun trackResponseStarted(event: EventData?) {
        val trackedEvent =
            InternalSuperwallEvent.PaywallLoad(
                state = InternalSuperwallEvent.PaywallLoad.State.Start(),
                eventData = event,
            )
        ioScope.launch { track(trackedEvent) }
    }

    private fun trackResponseLoaded(
        paywallInfo: PaywallInfo,
        event: EventData?,
    ) {
        val responseLoadEvent =
            InternalSuperwallEvent.PaywallLoad(
                InternalSuperwallEvent.PaywallLoad.State.Complete(paywallInfo = paywallInfo),
                eventData = event,
            )
        ioScope.launch { track(responseLoadEvent) }
    }

    suspend fun addProducts(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            var paywall = paywall

            paywall = trackProductsLoadStart(paywall, request)
            try {
                // Custom products (store == CUSTOM) come from /products, not Play Billing.
                // A /products failure is fatal — mirror BillingNotAvailable below.
                fetchAndCacheCustomProducts(paywall)
            } catch (error: Throwable) {
                paywall.productsLoadingInfo.failAt = Date()
                val productLoadFailEvent =
                    InternalSuperwallEvent.PaywallProductsLoad(
                        state = InternalSuperwallEvent.PaywallProductsLoad.State.Fail(error.message),
                        paywallInfo = paywall.getInfo(request.eventData),
                        eventData = request.eventData,
                    )
                ioScope.launch { track(productLoadFailEvent) }
                throw error
            }
            paywall = getProducts(paywall, request)
            paywall = trackProductsLoadFinish(paywall, request.eventData)

            return@withContext paywall
        }

    /**
     * Fetches custom products (ProductItem.StoreProductType.Custom) from the Superwall
     * /products endpoint and caches them in StoreManager so the downstream getProducts
     * flow finds them already loaded.
     *
     * Idempotent: skips entirely when no custom products need refreshing. A /products
     * failure is propagated (required = true) so [addProducts] surfaces it as a tracked
     * product-load failure instead of presenting a paywall with missing prices.
     */
    private suspend fun fetchAndCacheCustomProducts(paywall: Paywall) {
        val customIds =
            paywall.productItems
                .filter { it.type is ProductItem.StoreProductType.Custom }
                .map { it.fullProductId }
                .toSet()
        if (customIds.isEmpty()) return

        storeManager.fetchAndCacheCustomProducts(customIds, required = true)
    }

    private suspend fun getProducts(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            var paywall = paywall

            // Use local overrides if available, otherwise use global overrides
            val substituteProducts =
                request.overrides.products
                    ?: run {
                        val globalOverrides = getGlobalOverrides()
                        if (globalOverrides.isNotEmpty()) {
                            val productOverrides =
                                globalOverrides.mapValues { ProductOverride.ById(it.value) }
                            convertProductOverrides(productOverrides)
                        } else {
                            null
                        }
                    }

            val result =
                storeManager.getProducts(
                    substituteProducts = substituteProducts,
                    paywall = paywall,
                    request = request,
                )
            if (result.paywall != null) {
                paywall = result.paywall
            }
            paywall.productItems = result.productItems

            val outcome =
                PaywallLogic.getVariablesAndFreeTrial(
                    productItems = result.productItems,
                    productsByFullId = result.productsByFullId,
                    isFreeTrialAvailableOverride = request.overrides.isFreeTrial,
                    customerInfo = factory.currentCustomerInfo(),
                    introOfferEligibility = paywall.introOfferEligibility,
                )
            paywall.productVariables = outcome.productVariables
            paywall.isFreeTrialAvailable = outcome.isFreeTrialAvailable

            return@withContext paywall
        }

    // Analytics
    private fun trackProductsLoadStart(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall {
        paywall.productsLoadingInfo.startAt = Date()
        val paywallInfo = paywall.getInfo(request.eventData)
        val productLoadEvent =
            InternalSuperwallEvent.PaywallProductsLoad(
                state = InternalSuperwallEvent.PaywallProductsLoad.State.Start(),
                paywallInfo,
                request.eventData,
            )
        ioScope.launch { track(productLoadEvent) }
        return paywall
    }

    private fun trackProductsLoadFinish(
        paywall: Paywall,
        event: EventData?,
    ): Paywall {
        paywall.productsLoadingInfo.endAt = Date()
        val paywallInfo = paywall.getInfo(event)
        val productLoadEvent =
            InternalSuperwallEvent.PaywallProductsLoad(
                state = InternalSuperwallEvent.PaywallProductsLoad.State.Complete(),
                paywallInfo,
                event,
            )
        ioScope.launch { track(productLoadEvent) }

        return paywall
    }

    internal fun resetCache() {
        paywallsByHash.clear()
    }

    fun removeCachedPaywalls(identifiers: Set<String>) {
        paywallsByHash.entries.removeAll { it.value.identifier in identifiers }
    }

    /**
     * Converts productOverrides to the format expected by StoreManager.getProducts.
     * This function handles ProductOverride.ByProduct objects by extracting the StoreProduct,
     * and ignores ProductOverride.ById objects since they need to be resolved by the StoreManager.
     */
    private suspend fun convertProductOverrides(productOverrides: Map<String, ProductOverride>?): Map<String, StoreProduct>? {
        if (productOverrides.isNullOrEmpty()) return null
        val convertedProducts = mutableMapOf<String, StoreProduct?>()
        val products =
            storeManager.getProductsWithoutPaywall(
                productOverrides.values
                    .map {
                        when (it) {
                            is ProductOverride.ById -> it.productId
                            is ProductOverride.ByProduct -> it.product.productIdentifier
                        }
                    }.toList(),
            )
        for ((name, override) in productOverrides) {
            when (override) {
                is ProductOverride.ByProduct -> {
                    convertedProducts[name] = override.product
                }

                is ProductOverride.ById -> {
                    val product = products[override.productId]
                    convertedProducts[name] = product
                }
            }
        }

        return (convertedProducts.filterNot { it.value == null } as Map<String, StoreProduct>).ifEmpty { null }
    }
}
