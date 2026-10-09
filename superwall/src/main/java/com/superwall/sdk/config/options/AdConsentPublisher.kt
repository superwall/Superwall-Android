package com.superwall.sdk.config.options

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends the current ad consent to Superwall as device and config attributes.
 *
 * Sends run one at a time, and one superseded by a later [publish] is dropped, so
 * an older snapshot is never tracked after a newer one. A send waits for [after],
 * so it can't be queued before an events-queue change it follows.
 */
internal class AdConsentPublisher(
    private val scope: CoroutineScope,
    private val track: suspend (Trackable) -> Unit,
    private val makeDeviceAttributes: suspend () -> HashMap<String, Any>,
    private val makeConfigAttributes: () -> Trackable,
) {
    private val generation = AtomicLong()
    private val mutex = Mutex()

    fun publish(after: Job? = null): Job {
        val current = generation.incrementAndGet()
        return scope.launch {
            after?.join()
            mutex.withLock {
                if (current != generation.get()) {
                    return@withLock
                }
                track(InternalSuperwallEvent.DeviceAttributes(makeDeviceAttributes()))
                track(makeConfigAttributes())
            }
        }
    }
}
