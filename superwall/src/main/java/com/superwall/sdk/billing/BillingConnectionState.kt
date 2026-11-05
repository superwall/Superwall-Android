package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.superwall.sdk.misc.primitives.Reducer
import kotlin.math.min

/**
 * A request waiting for the billing client to connect. [callback] gets `null` once the client
 * is ready, or the error that stopped it from connecting. [delayMs] delays the run once ready.
 */
internal class PendingRequest(
    val delayMs: Long?,
    val callback: (connectionError: BillingError?) -> Unit,
)

/** Whether a reconnect is already on its way, so a second failure doesn't schedule another. */
internal sealed interface ReconnectSchedule {
    object Idle : ReconnectSchedule

    object Scheduled : ReconnectSchedule
}

/**
 * Everything the billing connection keeps between Play callbacks: the client, whether billing is
 * available, the reconnect backoff, how many setups in a row failed, and the requests waiting
 * for a connection, oldest first.
 */
internal data class ConnectionState(
    val client: BillingClient? = null,
    val availability: BillingAvailability = BillingAvailability.Unknown,
    val backoffMs: Long = RECONNECT_TIMER_START_MILLISECONDS,
    val retry: ReconnectSchedule = ReconnectSchedule.Idle,
    val failures: Int = 0,
    val pendingRequests: List<PendingRequest> = emptyList(),
) {
    val isReady: Boolean
        get() = client?.isReady == true

    // -----------------------------------------------------------------------
    // Pure state changes — (ConnectionState) -> ConnectionState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (ConnectionState) -> ConnectionState,
    ) : Reducer<ConnectionState> {
        /** The billing client was created. */
        data class ClientCreated(
            val client: BillingClient,
        ) : Updates({ it.copy(client = client) })

        /** The billing client was dropped, so the next connection attempt creates a new one. */
        object ClientCleared : Updates({ it.copy(client = null) })

        /** A connection attempt starts, so a scheduled reconnect has now run. */
        object ConnectionStarted : Updates({ it.copy(retry = ReconnectSchedule.Idle) })

        /** [request] waits for the client to connect, behind those already queued. */
        data class Enqueue(
            val request: PendingRequest,
        ) : Updates({ it.copy(pendingRequests = it.pendingRequests + request) })

        /** The queued requests were handed out, to run or to fail. */
        object RequestsTaken : Updates({ it.copy(pendingRequests = emptyList()) })

        /** Setup succeeded: billing is available and the backoff and failure count reset. */
        object Connected : Updates({
            it.copy(
                availability = BillingAvailability.Available,
                backoffMs = RECONNECT_TIMER_START_MILLISECONDS,
                failures = 0,
            )
        })

        /** Billing can't be used on this device; requests fail with [error] until a re-probe. */
        data class MarkUnavailable(
            val error: BillingError.BillingNotAvailable,
        ) : Updates({ it.copy(availability = BillingAvailability.Unavailable(error)) })

        /**
         * A setup failed for a reason that may pass. After [MAX_TRANSIENT_SETUP_FAILURES] in a row
         * billing is treated as unavailable, so requests stop waiting on a connection that may
         * never come.
         */
        data class TransientSetupFailure(
            val debugMessage: String,
            val responseCode: Int,
        ) : Updates({ state ->
                val failures = state.failures + 1
                val reachedLimit =
                    failures >= MAX_TRANSIENT_SETUP_FAILURES &&
                        state.availability !is BillingAvailability.Unavailable
                state.copy(
                    failures = failures,
                    availability =
                        if (reachedLimit) {
                            BillingAvailability.Unavailable(
                                BillingError.BillingNotAvailable(
                                    "Billing is not available in this device. Setup failed $failures " +
                                        "times in a row. Last error: $debugMessage " +
                                        "ErrorCode: $responseCode.",
                                ),
                            )
                        } else {
                            state.availability
                        },
                )
            })

        /** A reconnect is scheduled after [ConnectionState.backoffMs]; the next one waits twice as long. */
        object ReconnectScheduled : Updates({
            it.copy(
                retry = ReconnectSchedule.Scheduled,
                backoffMs = min(it.backoffMs * 2, RECONNECT_TIMER_MAX_TIME_MILLISECONDS),
            )
        })

        /** The app came back to the foreground while unavailable: probe billing again from scratch. */
        object Reprobe : Updates({
            it.copy(availability = BillingAvailability.Unknown, failures = 0)
        })
    }
}
