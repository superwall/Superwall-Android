package com.superwall.sdk.logger

import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.SuperwallOptions

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
        ): Boolean {
            if (Superwall.initialized) {
                val delegateAdapter = Superwall.instance.dependencyContainer.delegateAdapter
                if (delegateAdapter.kotlinDelegate != null || delegateAdapter.javaDelegate != null) {
                    return true
                }
            }
            return shouldPrint(logLevel, scope)
        }

        @PublishedApi
        internal fun emit(
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

            val dumping: MutableMap<String, Any> = mutableMapOf()

            info?.let {
                dumping["info"] = it
            }

            error?.let {
                dumping["error"] = it
            }

            val name =
                "\n${logLevel.getDescriptionEmoji()} [!!Superwall] [$scope] $logLevel: $message\n"

            if (dumping.isEmpty()) {
                println(name)
            } else {
                dumping.forEach { (key, value) ->
                    println("$key: $value")
                }
            }
        }

        fun debug(
            logLevel: LogLevel,
            scope: LogScope,
            message: String = "",
            info: Map<String, Any>? = mapOf(),
            error: Throwable? = null,
        ) {
            emit(logLevel, scope, message, info, error)
        }

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
