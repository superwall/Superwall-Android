package com.superwall.sdk.paywall.request

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.dependencies.ConfigManagerFactory
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import java.util.Date

interface PaywallRequestManagerDepFactory :
    DeviceInfoFactory,
    ConfigManagerFactory

class PaywallRequestManager(
    private val storeKitManager: StoreKitManager,
    private val network: Network,
    private val factory: PaywallRequestManagerDepFactory,
    private val ioScope: CoroutineScope,
) {
    // Single thread context to make this class similar to an actor. All functions in this class
    // must execute with this context.

    private val activeTasks: MutableMap<String, Deferred<Paywall>> = mutableMapOf()
    private val paywallsByHash: MutableMap<String, Paywall> = mutableMapOf()

    suspend fun getPaywall(request: PaywallRequest): Paywall =
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

            var paywall = paywallsByHash[requestHash]
            if (paywall != null && !request.isDebuggerLaunched) {
                return@withContext updatePaywall(paywall, request)
            }

            val existingTask = activeTasks[requestHash]
            if (existingTask != null) {
                var paywall = existingTask.await()
                paywall = updatePaywall(paywall, request)
                return@withContext paywall
            }

            val deferredTask = CompletableDeferred<Paywall>()
            activeTasks[requestHash] = deferredTask

            try {
                val rawPaywall =
                    getRawPaywall(request)
                val finalPaywall =
                    addProducts(rawPaywall, request)
                saveRequestHash(requestHash, finalPaywall, request.isDebuggerLaunched)

                deferredTask.complete(finalPaywall)
            } catch (error: Throwable) {
                activeTasks.remove(requestHash)
                deferredTask.completeExceptionally(error)
            }

            paywall = deferredTask.await()
            paywall = updatePaywall(paywall, request)

            return@withContext paywall
        }

    private suspend fun updatePaywall(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            val paywall = paywall
            paywall.experiment = request.responseIdentifiers.experiment
            paywall.presentationSourceType = request.presentationSourceType
            return@withContext paywall
        }

    private suspend fun saveRequestHash(
        requestHash: String,
        paywall: Paywall,
        isDebuggerLaunched: Boolean,
    ) = withContext(ioScope.coroutineContext) {
        activeTasks.remove(requestHash)
        if (!isDebuggerLaunched) {
            paywallsByHash[requestHash] = paywall
        }
    }

    suspend fun getRawPaywall(request: PaywallRequest): Paywall =

        withContext(ioScope.coroutineContext) {
            println("!!getRawPaywall - ${request.responseIdentifiers.paywallId}")
            trackResponseStarted(event = request.eventData)
            val paywall = getPaywallResponse(request)

            val paywallInfo =
                paywall.getInfo(
                    fromEvent = request.eventData,
                )
            trackResponseLoaded(
                paywallInfo,
                event = request.eventData,
            )

            return@withContext paywall
        }

    private suspend fun getPaywallResponse(request: PaywallRequest): Paywall =
        withContext(ioScope.coroutineContext) {
            val responseLoadStartTime = Date()
            val paywallId = request.responseIdentifiers.paywallId
            val event = request.eventData

            val paywall: Paywall =
                try {
                    factory.makeStaticPaywall(
                        paywallId = paywallId,
                        isDebuggerLaunched = request.isDebuggerLaunched,
                    ) ?: network.getPaywall(
                        identifier = paywallId,
                        event = event,
                    )
                } catch (error: Throwable) {
                    val errorResponse =
                        PaywallLogic.handlePaywallError(
                            error,
                            event,
                        )
                    throw errorResponse
                }

            println("!!getPaywallResponse - $paywallId - $paywall")

            paywall.experiment = request.responseIdentifiers.experiment
            paywall.responseLoadingInfo.startAt = responseLoadStartTime
            paywall.responseLoadingInfo.endAt = Date()

            println("!!getPaywallResponse - $paywallId - $paywall - ${paywall.experiment}")

            return@withContext paywall
        }

    // MARK: - Analytics
    private suspend fun trackResponseStarted(event: EventData?) =
        withContext(ioScope.coroutineContext) {
            val trackedEvent =
                InternalSuperwallEvent.PaywallLoad(
                    state = InternalSuperwallEvent.PaywallLoad.State.Start(),
                    eventData = event,
                )
            Superwall.instance.track(trackedEvent)
        }

    private suspend fun trackResponseLoaded(
        paywallInfo: PaywallInfo,
        event: EventData?,
    ) = withContext(ioScope.coroutineContext) {
        val responseLoadEvent =
            InternalSuperwallEvent.PaywallLoad(
                InternalSuperwallEvent.PaywallLoad.State.Complete(paywallInfo = paywallInfo),
                eventData = event,
            )
        Superwall.instance.track(responseLoadEvent)
    }

    suspend fun addProducts(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            var paywall = paywall

            paywall = trackProductsLoadStart(paywall, request)
            paywall =
                try {
                    getProducts(paywall, request)
                } catch (error: Throwable) {
                    throw error
                }
            paywall = trackProductsLoadFinish(paywall, request.eventData)

            return@withContext paywall
        }

    private suspend fun getProducts(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            var paywall = paywall

            val result =
                storeKitManager.getProducts(
                    substituteProducts = request.overrides.products,
                    paywall = paywall,
                    request = request,
                )
            paywall = result.paywall
            paywall.productItems = result.productItems

            val outcome =
                PaywallLogic.getVariablesAndFreeTrial(
                    productItems = result.productItems,
                    productsByFullId = result.productsByFullId,
                    isFreeTrialAvailableOverride = request.overrides.isFreeTrial,
                )
            paywall.productVariables = outcome.productVariables
            paywall.isFreeTrialAvailable = outcome.isFreeTrialAvailable

            return@withContext paywall
        }

    // Analytics
    private suspend fun trackProductsLoadStart(
        paywall: Paywall,
        request: PaywallRequest,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            var paywall = paywall
            paywall.productsLoadingInfo.startAt = Date()
            val paywallInfo = paywall.getInfo(request.eventData)
            val productLoadEvent =
                InternalSuperwallEvent.PaywallProductsLoad(
                    state = InternalSuperwallEvent.PaywallProductsLoad.State.Start(),
                    paywallInfo,
                    request.eventData,
                )
            Superwall.instance.track(productLoadEvent)
            return@withContext paywall
        }

    private suspend fun trackProductsLoadFinish(
        paywall: Paywall,
        event: EventData?,
    ): Paywall =
        withContext(ioScope.coroutineContext) {
            var paywall = paywall
            paywall.productsLoadingInfo.endAt = Date()
            val paywallInfo = paywall.getInfo(event)
            val productLoadEvent =
                InternalSuperwallEvent.PaywallProductsLoad(
                    state = InternalSuperwallEvent.PaywallProductsLoad.State.Complete(),
                    paywallInfo,
                    event,
                )
            Superwall.instance.track(productLoadEvent)

            return@withContext paywall
        }

    internal fun resetCache() {
        paywallsByHash.clear()
    }
}
