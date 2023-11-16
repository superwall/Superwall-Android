package com.superwall.sdk.billing

import Logger
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Collections.min
import kotlin.math.min

private const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
private const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L // 15 minutes

// Extends the PurchaseUpdatedListener to add onConnected
interface BillingWrapperInterface: PurchasesUpdatedListener {
   fun onConnected()
}

class SharedGoogleBillingClientWrapper(val context: Context, val mainHandler: Handler = Handler(Looper.getMainLooper())): PurchasesUpdatedListener,
    BillingClientStateListener {

    val isConnected = MutableStateFlow(false)

    // how long before the data source tries to reconnect to Google play
    private var reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS

    @get:Synchronized
    @set:Synchronized
    private var reconnectionAlreadyScheduled = false


    @get:Synchronized
    @set:Synchronized
    @Volatile
    var billingClient: BillingClient? = null

    @get:Synchronized
    @set:Synchronized
    @Volatile
    var hasCalledStartConnection: Boolean = false


    val listeners: MutableMap<String, BillingWrapperInterface> = mutableMapOf()

    @Synchronized
    fun addListener(key: String, listener: BillingWrapperInterface) {
        listeners[key] = listener
        if (isConnected.value) {
            listener.onConnected()
        }
    }

    public fun removeListener(key: String) {
        listeners.remove(key)
    }

    fun startConnectionOnMainThread(delayMilliseconds: Long, force: Boolean = false) {
        mainHandler.postDelayed(
            { startConnection() },
            delayMilliseconds,
        )
    }

    fun startConnection(force: Boolean = false) {
        synchronized(this@SharedGoogleBillingClientWrapper) {
            if (billingClient == null) {
                billingClient = BillingClient.newBuilder(context)
                    .setListener(this@SharedGoogleBillingClientWrapper)
                    .enablePendingPurchases()
                    .build()
            }

            // If we've already called startConnection, don't call it again unless we're forcing it
            // based on a retry
            if (hasCalledStartConnection && !force) {
                return
            }
            hasCalledStartConnection = true

            billingClient?.let {
                if (!it.isReady) {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.productsManager,
                        "Starting billing client",
                    )
                    try {
                        it.startConnection(this@SharedGoogleBillingClientWrapper)
                    } catch (e: IllegalStateException) {
                        Logger.debug(
                            LogLevel.error,
                            LogScope.productsManager,
                            "IllegalStateException when connecting to billing client: ${e.message}",
                        )
                        // TODO: Maybe invalidate the billing client and try again?
                    }
                }
            }
        }
    }

    public suspend fun waitForConnectedClient(receivingFunction: BillingClient.() -> Unit) {
        // Call this every time to make sure we're waiting for the client to connect
        startConnectionOnMainThread(0)
        isConnected.first { it }
        withConnectedClient {
            receivingFunction()
        }
    }

    private fun withConnectedClient(receivingFunction: BillingClient.() -> Unit) {
        billingClient?.takeIf { it.isReady }?.let {
            it.receivingFunction()
        } ?: Logger.debug(
            LogLevel.error,
            LogScope.productsManager,
            "Billing client not ready",
        )
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, p1: MutableList<Purchase>?) {
        // Call back to all listeners
        Logger.debug(
            LogLevel.info,
            LogScope.productsManager,
            "onPurchasesUpdated: ${billingResult.responseCode}",
        )

        listeners.forEach { s, purchasesUpdatedListener ->
            purchasesUpdatedListener.onPurchasesUpdated(billingResult, p1)
        }
    }

    override fun onBillingServiceDisconnected() {
        Logger.debug(
            LogLevel.debug,
            LogScope.productsManager,
            "Billing client disconnected",
        )
        isConnected.value = false
        retryBillingServiceConnectionWithExponentialBackoff()
    }

    /**
     * Retries the billing service connection with exponential backoff, maxing out at the time
     * specified by RECONNECT_TIMER_MAX_TIME_MILLISECONDS.
     *
     * This prevents ANRs, see https://github.com/android/play-billing-samples/issues/310
     */
    private fun retryBillingServiceConnectionWithExponentialBackoff() {
        Logger.debug(
            LogLevel.error,
            LogScope.productsManager,
            "Billing client disconnected, retrying in $reconnectMilliseconds milliseconds",
        )
        startConnectionOnMainThread(reconnectMilliseconds, true)
        reconnectMilliseconds = min(
            reconnectMilliseconds * 2,
            RECONNECT_TIMER_MAX_TIME_MILLISECONDS,
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
                    isConnected.value = true
                    reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
                    listeners.forEach { s, purchasesUpdatedListener ->
                        purchasesUpdatedListener.onConnected()
                    }
                }

                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, not supported or unavailable: ${billingResult.responseCode}",
                    )
                    // The calls will fail with an error that will be surfaced. We want to surface these errors
                    // Can't call executePendingRequests because it will not do anything since it checks for isReady()
                }

                BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                BillingClient.BillingResponseCode.ERROR,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.USER_CANCELED,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, retrying: ${billingResult.responseCode}"
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
                        "Billing client error, item unavi supported or unavailable: ${billingResult.responseCode}",
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
}