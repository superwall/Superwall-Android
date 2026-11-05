package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.StoreContext
import com.superwall.sdk.misc.primitives.TypedAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE =
    "Google Play In-app Billing API version is less than 3"

/**
 * What the billing connection's actions run against: the actor holding [ConnectionState], the
 * scope requests run on, and the Play client factory and listeners.
 */
internal interface BillingConnectionContext : StoreContext<ConnectionState, BillingConnectionContext> {
    /** Builds the Play billing client, reporting purchases to [purchasesListener]. */
    val createBillingClient: (PurchasesUpdatedListener) -> BillingClient
    val purchasesListener: PurchasesUpdatedListener

    /** Receives Play's setup and disconnect callbacks, which dispatch back onto this actor. */
    val stateListener: BillingClientStateListener
}

/**
 * Hands [request] its result on [StoreContext.scope]: `null` once connected (after its delay),
 * or the [error] that stopped the connection. Requests never run on the actor's queue, so a slow
 * one can't hold up the next connection callback.
 */
private fun BillingConnectionContext.deliver(
    request: PendingRequest,
    error: BillingError?,
) {
    scope.launch {
        if (error == null) request.delayMs?.let { delay(it) }
        request.callback(error)
    }
}

/** The connection's async work. Each runs on the [SequentialActor], in dispatch order. */
internal sealed class ConnectionActions(
    override val execute: suspend BillingConnectionContext.() -> Unit,
) : TypedAction<BillingConnectionContext> {
    /**
     * Creates the client if needed and starts connecting it. A second start while one is in
     * flight would be answered with DEVELOPER_ERROR and do nothing, so it's skipped.
     */
    object Connect : ConnectionActions(exec@{
        val client =
            state.value.client ?: try {
                createBillingClient(purchasesListener).also { update(ConnectionState.Updates.ClientCreated(it)) }
            } catch (e: Throwable) {
                immediate(
                    MarkUnavailable(
                        BillingError.BillingNotAvailable(
                            "Billing is not available in this device. " +
                                "The billing client could not be created: ${e.message}",
                        ),
                    ),
                )
                return@exec
            }

        update(ConnectionState.Updates.ConnectionStarted)

        if (client.connectionState == BillingClient.ConnectionState.CONNECTING || client.isReady) return@exec

        Logger.debug(LogLevel.debug, LogScope.productsManager, "Starting billing client")
        try {
            client.startConnection(stateListener)
        } catch (e: IllegalStateException) {
            Logger.debug(
                LogLevel.error,
                LogScope.productsManager,
                "IllegalStateException when connecting to billing client: ${e.message}",
            )
            immediate(FailAll(BillingError.IllegalStateException))
        }
    })

    /** Starts connecting after [delayMs]. */
    data class ConnectAfter(
        val delayMs: Long,
    ) : ConnectionActions({
            scope.launch {
                delay(delayMs)
                effect(Connect)
            }
        })

    /**
     * [request] needs a connected client. It fails straight away while billing is unavailable;
     * otherwise it waits in the queue, which drains now if the client is ready.
     */
    data class Request(
        val request: PendingRequest,
    ) : ConnectionActions({
            val current = state.value
            val unavailable = current.availability as? BillingAvailability.Unavailable
            when {
                unavailable != null -> deliver(request, unavailable.error)
                else -> {
                    update(ConnectionState.Updates.Enqueue(request))
                    if (current.client?.isReady == false) immediate(Connect) else immediate(Drain)
                }
            }
        })

    /**
     * Like [Request], but queues even while billing is unavailable and always tries to connect,
     * for callers that want Play asked again rather than a cached failure.
     */
    data class AwaitConnection(
        val request: PendingRequest,
    ) : ConnectionActions({
            update(ConnectionState.Updates.Enqueue(request))
            if (state.value.isReady) immediate(Drain) else immediate(Connect)
        })

    /** Hands every queued request to run, oldest first, while the client is ready. */
    object Drain : ConnectionActions(exec@{
        val current = state.value
        if (!current.isReady || current.pendingRequests.isEmpty()) return@exec
        update(ConnectionState.Updates.RequestsTaken)
        current.pendingRequests.forEach { deliver(it, null) }
    })

    /** Fails every queued request with [error], oldest first. */
    data class FailAll(
        val error: BillingError,
    ) : ConnectionActions(exec@{
            val pending = state.value.pendingRequests
            if (pending.isEmpty()) return@exec
            update(ConnectionState.Updates.RequestsTaken)
            pending.forEach { deliver(it, error) }
        })

    /** Billing can't be used here: remember it and fail everything waiting. */
    data class MarkUnavailable(
        val error: BillingError.BillingNotAvailable,
    ) : ConnectionActions({
            update(ConnectionState.Updates.MarkUnavailable(error))
            immediate(FailAll(error))
        })

    /**
     * Reconnects after the current backoff, which then doubles up to
     * [RECONNECT_TIMER_MAX_TIME_MILLISECONDS]. Only one reconnect is scheduled at a time.
     *
     * This prevents ANRs, see https://github.com/android/play-billing-samples/issues/310
     */
    object ScheduleReconnect : ConnectionActions({
        val current = state.value
        when (current.retry) {
            ReconnectSchedule.Scheduled ->
                Logger.debug(LogLevel.error, LogScope.productsManager, "Billing client retry already scheduled.")

            ReconnectSchedule.Idle -> {
                Logger.debug(
                    LogLevel.error,
                    LogScope.productsManager,
                    "Billing client disconnected, retrying in ${current.backoffMs} milliseconds",
                )
                update(ConnectionState.Updates.ReconnectScheduled)
                immediate(ConnectAfter(current.backoffMs))
            }
        }
    })

    /**
     * The client disconnected. Queued requests only run from a setup callback, and a request that
     * finds the client disconnected queues itself again, so reconnect if any are waiting.
     */
    object Disconnected : ConnectionActions({
        Logger.debug(LogLevel.debug, LogScope.productsManager, "Billing client disconnected")
        if (state.value.pendingRequests.isNotEmpty()) immediate(ScheduleReconnect)
    })

    /** Play finished setting up the client with [result]. */
    data class SetupFinished(
        val result: BillingResult,
    ) : ConnectionActions({
            when (val outcome = SetupOutcome.from(result)) {
                SetupOutcome.Connected -> {
                    Logger.debug(LogLevel.debug, LogScope.productsManager, "Billing client connected")
                    update(ConnectionState.Updates.Connected)
                    immediate(Drain)
                    state.value.client?.let(::logIfProductDetailsUnsupported)
                }

                is SetupOutcome.Unavailable -> {
                    Logger.debug(LogLevel.error, LogScope.productsManager, outcome.error.message ?: "Billing is not available.")
                    immediate(MarkUnavailable(outcome.error))
                }

                SetupOutcome.Transient -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, retrying: ${result.responseCode}",
                    )
                    val wasUnavailable = state.value.availability is BillingAvailability.Unavailable
                    update(ConnectionState.Updates.TransientSetupFailure(result.debugMessage, result.responseCode))
                    val now = state.value.availability
                    if (!wasUnavailable && now is BillingAvailability.Unavailable) immediate(FailAll(now.error))
                    immediate(ScheduleReconnect)
                }

                SetupOutcome.ItemError ->
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, item not supported or unavailable: ${result.responseCode}",
                    )

                // Reported when startConnection is called while a connection is already in flight.
                // That connection delivers its own result, so there's nothing to do while it's
                // still connecting (or connected). If it has since dropped, no callback is coming
                // for the queued requests, so reconnect.
                SetupOutcome.DuplicateConnection -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.productsManager,
                        "Billing client error, developer error: ${result.responseCode}",
                    )
                    val current = state.value
                    val connectionState = current.client?.connectionState
                    if (connectionState != BillingClient.ConnectionState.CONNECTING &&
                        connectionState != BillingClient.ConnectionState.CONNECTED &&
                        current.pendingRequests.isNotEmpty()
                    ) {
                        immediate(ScheduleReconnect)
                    }
                }

                SetupOutcome.Ignored -> Unit
            }
        })

    /** The app came back to the foreground: if billing was unavailable, probe it again from scratch. */
    object Reprobe : ConnectionActions(exec@{
        if (state.value.availability !is BillingAvailability.Unavailable) return@exec
        update(ConnectionState.Updates.Reprobe)
        immediate(Connect)
    })
}

/** What a setup [BillingResult] means for the connection. */
internal sealed interface SetupOutcome {
    object Connected : SetupOutcome

    /** Billing can't be used on this device, with the error requests fail with. */
    data class Unavailable(
        val error: BillingError.BillingNotAvailable,
    ) : SetupOutcome

    /** A failure that may pass, so setup is retried with backoff. */
    object Transient : SetupOutcome

    /** An item-level code, which says nothing about the connection. */
    object ItemError : SetupOutcome

    /** DEVELOPER_ERROR: a second startConnection while one was in flight. */
    object DuplicateConnection : SetupOutcome

    /** Any other code, which the connection doesn't act on. */
    object Ignored : SetupOutcome

    companion object {
        fun from(result: BillingResult): SetupOutcome =
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> Connected

                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                -> Unavailable(unavailableError(result))

                BillingClient.BillingResponseCode.ERROR,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.USER_CANCELED,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.NETWORK_ERROR,
                -> Transient

                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
                BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
                -> ItemError

                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> DuplicateConnection

                else -> Ignored
            }

        /**
         * Google sends "In-app Billing API version is less than 3" when there's no Google account,
         * no Play Store (badly configured emulators), or the Play Store caches are corrupted after
         * a language change. Version 3 is from 2012, so the message gets a clearer explanation.
         * See https://github.com/RevenueCat/purchases-android/issues/1288
         */
        private fun unavailableError(result: BillingResult): BillingError.BillingNotAvailable {
            val original = "DebugMessage: ${result.debugMessage} ErrorCode: ${result.responseCode}."
            val message =
                if (result.debugMessage == IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE) {
                    "Billing is not available in this device. Make sure there's an " +
                        "account configured in Play Store. Reopen the Play Store or clean its caches if this " +
                        "keeps happening. Original error message: $original"
                } else {
                    "Billing is not available in this device. Original error message: $original"
                }
            return BillingError.BillingNotAvailable(message)
        }
    }
}

private fun logIfProductDetailsUnsupported(client: BillingClient) {
    val result = client.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
    if (result.responseCode == BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED) {
        Logger.debug(
            LogLevel.error,
            LogScope.productsManager,
            "Product details not supported: ${result.responseCode} ${result.debugMessage}",
        )
    }
}

/**
 * The Play billing connection: one client, its availability, the reconnect backoff and the
 * requests waiting for a connection, all held in [ConnectionState] on a [SequentialActor].
 *
 * Play callbacks and callers dispatch actions instead of locking, so they apply one at a time in
 * the order they arrived. Requests themselves run on [scope], off the actor's queue.
 */
internal class BillingConnection(
    override val scope: CoroutineScope,
    override val createBillingClient: (PurchasesUpdatedListener) -> BillingClient,
    override val purchasesListener: PurchasesUpdatedListener,
    override val stateListener: BillingClientStateListener,
    override val actor: StateActor<BillingConnectionContext, ConnectionState> =
        SequentialActor(ConnectionState(), scope),
) : BillingConnectionContext {
    private val _availability = MutableStateFlow(actor.state.value.availability)

    /** Whether Play Billing can be used, kept in step with [ConnectionState.availability]. */
    val availability: StateFlow<BillingAvailability> = _availability.asStateFlow()

    init {
        actor.onUpdate { reducer, next ->
            next(reducer)
            _availability.value = actor.state.value.availability
        }
    }

    /** The billing client, once created. */
    val client: BillingClient?
        get() = state.value.client

    /** Replaces the client; `null` makes the next connection attempt create a new one. */
    fun setClient(client: BillingClient?) =
        update(client?.let { ConnectionState.Updates.ClientCreated(it) } ?: ConnectionState.Updates.ClientCleared)

    fun connect(delayMs: Long = 0) =
        effect(if (delayMs > 0) ConnectionActions.ConnectAfter(delayMs) else ConnectionActions.Connect)

    /** Runs [callback] once connected, or with the error that stopped the connection. */
    fun request(
        delayMs: Long? = null,
        callback: (BillingError?) -> Unit,
    ) = effect(ConnectionActions.Request(PendingRequest(delayMs, callback)))

    /**
     * Suspends until the client is ready, connecting even if billing was marked unavailable.
     * Fails after [timeoutMs] or with the error that stopped the connection.
     */
    suspend fun awaitConnection(timeoutMs: Long): Result<Unit> {
        if (state.value.isReady) return Result.success(Unit)
        val connected = CompletableDeferred<BillingError?>()
        effect(ConnectionActions.AwaitConnection(PendingRequest(null) { connected.complete(it) }))
        // The deferred completes with `null` on success, so map it to a Result inside the timeout:
        // only a real timeout leaves withTimeoutOrNull's own `null`.
        return withTimeoutOrNull(timeoutMs) {
            connected.await()?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
        } ?: Result.failure(Throwable("Billing connection timed out"))
    }

    fun setupFinished(result: BillingResult) = effect(ConnectionActions.SetupFinished(result))

    fun disconnected() = effect(ConnectionActions.Disconnected)

    fun reprobe() = effect(ConnectionActions.Reprobe)
}
