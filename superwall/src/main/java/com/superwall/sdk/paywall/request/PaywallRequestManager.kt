package com.superwall.sdk.paywall.request

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.trigger_session.LoadState
import com.superwall.sdk.dependencies.ConfigManagerFactory
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

interface PaywallRequestManagerDepFactory : DeviceInfoFactory,
    TriggerSessionManagerFactory,
    ConfigManagerFactory


class PaywallRequestManager(
    private val storeKitManager: StoreKitManager,
    private val network: Network,
    private val factory: PaywallRequestManagerDepFactory
) {
    private val activeTasks: MutableMap<String, Deferred<Paywall>> = mutableMapOf()
    private val paywallsByHash: MutableMap<String, Paywall> = mutableMapOf()


    private val mutex = Mutex()

    suspend fun getPaywall(request: PaywallRequest): Paywall = coroutineScope {
        println("!!getPaywall")
        val deviceInfo = factory.makeDeviceInfo()
        val requestHash = PaywallLogic.requestHash(
            identifier = request.responseIdentifiers.paywallId,
            event = request.eventData,
            locale = deviceInfo.locale,
            paywallProducts = request.overrides.products
        )

        val paywall = mutex.withLock { paywallsByHash[requestHash] }
        if (paywall != null && request.isDebuggerLaunched) {
            return@coroutineScope paywall.also {
                it.experiment = request.responseIdentifiers.experiment
            }
        }

        val existingTask = mutex.withLock { activeTasks[requestHash] }
        if (existingTask != null) {
            return@coroutineScope existingTask.await()
        }

        val task = async {
            try {
                val rawPaywall = getRawPaywall(request)
                val finalPaywall = addProducts(rawPaywall, request)

                saveRequestHash(requestHash, finalPaywall, !request.isDebuggerLaunched)

                finalPaywall
            } catch (error: Throwable) {
                mutex.withLock { activeTasks.remove(requestHash) }
                throw error
            }
        }

        mutex.withLock { activeTasks[requestHash] = task }

        println("!!getPaywall - await")
        task.await()
    }

    private suspend fun saveRequestHash(
        requestHash: String,
        paywall: Paywall,
        debuggerNotLaunched: Boolean
    ) {
        mutex.withLock {
            if (debuggerNotLaunched) {
                paywallsByHash[requestHash] = paywall
                activeTasks.remove(requestHash)
            }
        }
    }


    suspend fun getRawPaywall(request: PaywallRequest): Paywall {
        println("!!getRawPaywall - ${request.responseIdentifiers.paywallId}")
        trackResponseStarted(
            paywallId = request.responseIdentifiers.paywallId,
            event = request.eventData
        )
        val paywall = getPaywallResponse(request)

        val paywallInfo = paywall.getInfo(
            fromEvent = request.eventData,
            factory = factory
        )
        trackResponseLoaded(
            paywallInfo,
            event = request.eventData
        )

        return paywall
    }

    private suspend fun PaywallRequestManager.getPaywallResponse(request: PaywallRequest): Paywall {
        val responseLoadStartTime = Date()
        val paywallId = request.responseIdentifiers.paywallId
        val event = request.eventData
        val paywall: Paywall

        paywall = try {
            factory.makeStaticPaywall(paywallId = paywallId) ?: network.getPaywall(
                identifier = paywallId,
                event = event
            )
        } catch (error: Exception) {
            val triggerSessionManager = factory.getTriggerSessionManager()
            triggerSessionManager.trackPaywallResponseLoad(
                forPaywallId = request.responseIdentifiers.paywallId,
                state = LoadState.FAIL
            )
            val errorResponse = PaywallLogic.handlePaywallError(
                error,
                event
            )
            throw errorResponse
        }

        println("!!getPaywallResponse - ${paywallId} - ${paywall}")

        paywall.experiment = request.responseIdentifiers.experiment
        paywall.responseLoadingInfo.startAt = responseLoadStartTime
        paywall.responseLoadingInfo.endAt = Date()

       println("!!getPaywallResponse - ${paywallId} - ${paywall} - ${paywall.experiment}")

        return paywall
    }

    // MARK: - Analytics
    private suspend fun PaywallRequestManager.trackResponseStarted(
        paywallId: String?,
        event: EventData?
    ) {
        val triggerSessionManager = factory.getTriggerSessionManager()
        triggerSessionManager.trackPaywallResponseLoad(
            forPaywallId = paywallId,
            state = LoadState.START
        )
        val trackedEvent = InternalSuperwallEvent.PaywallLoad(
            state =  InternalSuperwallEvent.PaywallLoad.State.Start(),
            eventData = event
        )
        Superwall.instance.track(trackedEvent)
    }

    private suspend fun PaywallRequestManager.trackResponseLoaded(
        paywallInfo: PaywallInfo,
        event: EventData?
    ) {
        val responseLoadEvent = InternalSuperwallEvent.PaywallLoad(
            InternalSuperwallEvent.PaywallLoad.State.Complete(paywallInfo = paywallInfo),
            eventData = event
        )
        Superwall.instance.track(responseLoadEvent)

        val triggerSessionManager = factory.getTriggerSessionManager()
        triggerSessionManager.trackPaywallResponseLoad(
            forPaywallId = paywallInfo.databaseId,
            state = LoadState.END
        )
    }


    suspend fun addProducts(paywall: Paywall, request: PaywallRequest): Paywall {
        var paywall = paywall

        paywall = trackProductsLoadStart(paywall, request)
        paywall = try {
            getProducts(paywall, request)
        } catch (error: Exception) {
            throw error
        }
        paywall = trackProductsLoadFinish(paywall, request.eventData)

        return paywall
    }

    private suspend fun getProducts(paywall: Paywall, request: PaywallRequest): Paywall {
        var paywall = paywall

        try {
            val result = storeKitManager.getProducts(
                paywall.productIds,
                paywall.name,
                paywall.products,
                request.overrides.products
            )

            paywall.products = result.products

            val outcome = PaywallLogic.getVariablesAndFreeTrial(
                result.products,
                result.productsById,
                request.overrides.isFreeTrial,
                { id -> storeKitManager.isFreeTrialAvailable(id) }
            )
            paywall.productVariables = outcome.productVariables
//            paywall.swProductVariablesTemplate = outcome.swProductVariablesTemplate
            paywall.isFreeTrialAvailable = outcome.isFreeTrialAvailable

            return paywall
        } catch (error: Exception) {
            paywall.productsLoadingInfo.failAt = Date()
            val paywallInfo = paywall.getInfo(request.eventData, factory)
            trackProductLoadFail(paywallInfo, request.eventData)
            throw error
        }
    }

    // Analytics
    private suspend fun trackProductsLoadStart(paywall: Paywall, request: PaywallRequest): Paywall {
        var paywall = paywall
        paywall.productsLoadingInfo.startAt = Date()
        val paywallInfo = paywall.getInfo(request.eventData, factory)
        val productLoadEvent = InternalSuperwallEvent.PaywallProductsLoad(
            state = InternalSuperwallEvent.PaywallProductsLoad.State.Start(),
            paywallInfo,
            request.eventData
        )
        Superwall.instance.track(productLoadEvent)

        val triggerSessionManager = factory.getTriggerSessionManager()
        triggerSessionManager.trackProductsLoad(paywallInfo.databaseId, LoadState.START)
        return paywall
    }

    private suspend fun trackProductLoadFail(paywallInfo: PaywallInfo, event: EventData?) {
        val productLoadEvent = InternalSuperwallEvent.PaywallProductsLoad(
            state = InternalSuperwallEvent.PaywallProductsLoad.State.Fail(),
            paywallInfo = paywallInfo,
            eventData = event
        )
        Superwall.instance.track(productLoadEvent)

        val triggerSessionManager = factory.getTriggerSessionManager()
        triggerSessionManager.trackProductsLoad(paywallInfo.databaseId, LoadState.FAIL)
    }

    private suspend fun trackProductsLoadFinish(paywall: Paywall, event: EventData?): Paywall {
        var paywall = paywall
        paywall.productsLoadingInfo.endAt = Date()
        val paywallInfo = paywall.getInfo(event, factory)
        val triggerSessionManager = factory.getTriggerSessionManager()
        triggerSessionManager.trackProductsLoad(paywallInfo.databaseId, LoadState.END)
        val productLoadEvent = InternalSuperwallEvent.PaywallProductsLoad(
            state = InternalSuperwallEvent.PaywallProductsLoad.State.Complete(),
            paywallInfo,
            event
        )
        Superwall.instance.track(productLoadEvent)

        return paywall
    }
}