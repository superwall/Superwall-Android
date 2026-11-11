package com.superwall.sdk.paywall.view.webview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides whether a paywall page that failed to load is loaded again, and when.
 *
 * A load gets [maxRetries] reloads, each after a longer wait than the last, because a failure
 * such as a DNS error fails again at once if retried at once. The count belongs to the load, not
 * to a page: only [reset] restores it, so a failed page that still reports "finished", or an
 * error that arrives after a page finished, can't earn more reloads. Not thread-safe; call it
 * from one thread.
 */
internal class PaywallLoadRetrier(
    private val scope: CoroutineScope,
    private val maxRetries: Int = MAX_RETRIES,
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val reload: (url: String) -> Unit,
) {
    sealed interface Outcome {
        /** The page is loaded again after [delayMs]. */
        data class Scheduled(
            val delayMs: Long,
        ) : Outcome

        /** A reload is already waiting; it answers this failure too. */
        object AlreadyScheduled : Outcome

        /** The load has used all of its reloads. */
        object Exhausted : Outcome
    }

    private var retryCount = 0
    private var pending: Job? = null

    fun onLoadFailed(url: String): Outcome {
        // One failed load can report more than one error.
        if (pending?.isActive == true) return Outcome.AlreadyScheduled
        if (retryCount >= maxRetries) return Outcome.Exhausted
        val delayMs = baseDelayMs shl retryCount
        retryCount += 1
        pending =
            scope.launch {
                delay(delayMs)
                reload(url)
            }
        return Outcome.Scheduled(delayMs)
    }

    /** Starts a new load: restores the reloads and drops one that is waiting. */
    fun reset() {
        cancel()
        retryCount = 0
    }

    /** Drops a reload that is waiting. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        const val MAX_RETRIES = 3

        // Doubles with each retry: 0.5s, 1s, 2s.
        const val BASE_DELAY_MS = 500L
    }
}
