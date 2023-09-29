import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.SuperwallOptions

interface Loggable {
    companion object {
        fun shouldPrint(logLevel: LogLevel, scope: LogScope): Boolean {
            return true
            var logging: SuperwallOptions.Logging = SuperwallOptions.Logging()
            if (Superwall.initialized) {
                logging = Superwall.instance.options.logging
            }
            val exceedsCurrentLogLevel = logLevel.level >= (logging.level?.level ?: 99)
            val isInScope = logging.scopes.contains(scope)
            val allLogsActive = logging.scopes.contains(LogScope.all)

            return exceedsCurrentLogLevel && (isInScope || allLogsActive)
        }

        fun debug(
            logLevel: LogLevel,
            scope: LogScope,
            message: String = "",
            info: Map<String, Any>? = mapOf(),
            error: Throwable? = null
        ) {
//            Task.detached(priority = Task.Priority.utility) {
            val output: MutableList<String> = mutableListOf()
            val dumping: MutableMap<String, Any> = mutableMapOf()

            message?.let { output.add(it) }

            info?.let {
                output.add(it.toString())
                dumping["info"] = it
            }

            error?.let {
                output.add(it.localizedMessage ?: "")
                dumping["error"] = it
            }

            // TODO: Add logging to the delegate
            // Launch a coroutine to handle the logging

//                Superwall.instance.dependencyContainer.delegateAdapter.handleLog(
//                    level = logLevel.toString(),
//                    scope = scope.toString(),
//                    message = message,
//                    info = info,
//                    error = error
//                )

            if (!shouldPrint(logLevel, scope)) {
                return
            }

            val name =
                "\n${logLevel.getDescriptionEmoji()} [!!Superwall] [${scope.toString()}] ${logLevel.toString()}${if (message != null) ": $message" else ""}\n"

            if (dumping.isEmpty()) {
                println(name)
            } else {
                dumping.forEach { (key, value) ->
                    println("$key: $value")
                }
            }
        }
//        }
    }
}

enum class Logger : Loggable {
    ;

    companion object {
        fun shouldPrint(logLevel: LogLevel, scope: LogScope): Boolean {
            return Loggable.shouldPrint(logLevel, scope)
        }

        fun debug(
            logLevel: LogLevel,
            scope: LogScope,
            message: String = "",
            info: Map<String, Any>? = null,
            error: Throwable? = null
        ) {
            Loggable.debug(logLevel, scope, message, info, error)
        }
    }
}
