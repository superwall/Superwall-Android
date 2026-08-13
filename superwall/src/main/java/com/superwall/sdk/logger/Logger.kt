package com.superwall.sdk.logger

import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Delivers logs on a single background thread so that neither the console write nor the
 * customer's `SuperwallDelegate.handleLog` runs on the thread that produced the log.
 *
 * A single-threaded dispatcher drains its queue in FIFO order, so log ordering is preserved
 * and the multi-line console output of one log can no longer interleave with another's.
 */
internal object LogQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * Set by tests so that assertions don't have to await the log thread.
     */
    @Volatile
    internal var synchronous: Boolean = false

    fun post(block: () -> Unit) {
        if (synchronous) {
            runSafely(block)
        } else {
            scope.launch { runSafely(block) }
        }
    }

    /**
     * A throwing delegate must not take down the log thread, and must not be reported through
     * [Logger] itself as that would re-enter this queue.
     */
    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            println("[!!Superwall] Failed to deliver log: ${e.localizedMessage}")
        }
    }
}

interface Loggable {
    companion object {
        fun shouldPrint(
            logLevel: LogLevel,
            scope: LogScope,
        ): Boolean {
            var logging: SuperwallOptions.Logging = SuperwallOptions.Logging()
            if (Superwall.initialized) {
                logging = Superwall.instance.options.logging
            }
            if (logging.level == LogLevel.none) {
                return false
            }
            val exceedsCurrentLogLevel = logLevel.level >= logging.level.level
            val isInScope = logging.scopes.contains(scope)
            val allLogsActive = logging.scopes.contains(LogScope.all)

            return exceedsCurrentLogLevel && (isInScope || allLogsActive)
        }

        // True when a delegate will consume the log or it will be printed,
        // i.e. when it is worth building the log's message and info.
        @PublishedApi
        internal fun willLog(
            logLevel: LogLevel,
            scope: LogScope,
        ): Boolean = SuperwallDelegateAdapter.hasAnyDelegate || shouldPrint(logLevel, scope)

        @PublishedApi
        internal fun emit(
            logLevel: LogLevel,
            scope: LogScope,
            message: String,
            info: Map<String, Any>?,
            error: Throwable?,
        ) {
            LogQueue.post {
                deliver(logLevel, scope, message, info, error)
            }
        }

        private fun deliver(
            logLevel: LogLevel,
            scope: LogScope,
            message: String,
            info: Map<String, Any>?,
            error: Throwable?,
        ) {
            if (Superwall.initialized) {
                Superwall.instance.dependencyContainer.delegateAdapter.handleLog(
                    level = logLevel.toString(),
                    scope = scope.toString(),
                    message = message,
                    info = info,
                    error = error,
                )
            }

            if (!shouldPrint(logLevel, scope)) {
                return
            }

            println(
                "\n${logLevel.getDescriptionEmoji()} [!!Superwall] [$scope] $logLevel: $message\n",
            )

            info?.takeIf { it.isNotEmpty() }?.let { println("info: $it") }
            error?.let { println("error: $it") }
        }

        /**
         * Note that [info] is read on the log thread, so it must not be mutated after being
         * passed in.
         */
        fun debug(
            logLevel: LogLevel,
            scope: LogScope,
            message: String = "",
            info: Map<String, Any>? = mapOf(),
            error: Throwable? = null,
        ) {
            emit(logLevel, scope, message, info, error)
        }

        /**
         * Builds [message] and [info] only when something will consume the log. Note that
         * [info] is read on the log thread, so it must not be mutated after being passed in.
         */
        inline fun debug(
            logLevel: LogLevel,
            scope: LogScope,
            error: Throwable? = null,
            info: () -> Map<String, Any>? = { mapOf() },
            message: () -> String,
        ) {
            if (!willLog(logLevel, scope)) {
                return
            }
            emit(logLevel, scope, message(), info(), error)
        }
    }
}

object Logger : Loggable {
    fun shouldPrint(
        logLevel: LogLevel,
        scope: LogScope,
    ): Boolean = Loggable.shouldPrint(logLevel, scope)

    fun debug(
        logLevel: LogLevel,
        scope: LogScope,
        message: String = "",
        info: Map<String, Any>? = null,
        error: Throwable? = null,
    ) {
        Loggable.debug(logLevel, scope, message, info, error)
    }

    inline fun debug(
        logLevel: LogLevel,
        scope: LogScope,
        error: Throwable? = null,
        info: () -> Map<String, Any>? = { null },
        message: () -> String,
    ) {
        Loggable.debug(logLevel, scope, error, info, message)
    }
}
