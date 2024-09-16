package com.superwall.sdk.utilities

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.storage.ErrorLog
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface ErrorTracking {
    fun trackError(throwable: Throwable)

    @Serializable
    data class ErrorOccurence(
        @SerialName("message")
        val message: String,
        @SerialName("stacktrace")
        val stacktrace: String,
        @SerialName("timestamp")
        val timestamp: Long,
    )
}

/**
 * Used to track errors that occur in the SDK.
 * When an error occurs, it is stored in the cache and sent to the server when the SDK is initialized.
 * This ensures the error is logged even with the crash occurring. We only save a single error
 * in cache at a time, to ensure only the crashing error is logged. This also helps us avoid logging the
 * same error multiple times without relying on hashcodes or unique identifier.
 **/
internal class ErrorTracker(
    scope: CoroutineScope,
    private val cache: Storage,
    private val track: suspend (InternalSuperwallEvent.ErrorThrown) -> Unit = {
        Superwall.instance.track(
            it,
        )
    },
) : ErrorTracking {
    init {
        val exists = cache.get(ErrorLog)
        if (exists != null) {
            scope.launch {
                track(
                    InternalSuperwallEvent.ErrorThrown(
                        exists.message,
                        exists.stacktrace,
                        exists.timestamp,
                    ),
                )
                cache.remove(ErrorLog)
            }
        }
    }

    override fun trackError(throwable: Throwable) {
        val errorOccurence =
            ErrorTracking.ErrorOccurence(
                message = throwable.message ?: "",
                stacktrace = throwable.stackTraceToString(),
                timestamp = System.currentTimeMillis(),
            )
        cache.save(errorOccurence, ErrorLog)
    }
}

// Utility methods and closures for error tracking

internal fun Superwall.trackError(e: Throwable) {
    try {
        dependencyContainer.errorTracker.trackError(e)
    } catch (e: Exception) {
        throw e
    }
}

internal fun withErrorTracking(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        Superwall.instance.trackError(e)
        throw e
    }
}

internal suspend fun <T> withErrorTrackingAsync(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: Throwable) {
        Superwall.instance.trackError(e)
        throw e
    }
}

internal fun <T> withErrorTracking(block: () -> T): T {
    try {
        return block()
    } catch (e: Throwable) {
        Superwall.instance.trackError(e)
        throw e
    }
}
