package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import android.content.Context
import android.webkit.WebView
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxDeadException
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.misc.asEither
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.vc.web_view.webViewExists
import com.superwall.sdk.storage.LocalStorage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

class DefaultJavascriptEvalutor(
    private val ioScope: IOScope,
    private val uiScope: MainScope,
    private val context: Context,
    private val storage: LocalStorage,
    private val createSandbox: suspend (ctx: Context) -> Result<JavaScriptSandbox> = {
        asEither {
            JavaScriptSandbox.createConnectedInstanceAsync(it).await()
        }.toResult()
    },
) : JavascriptEvaluator {
    private val mutex = Mutex()
    private var eval: Deferred<JavascriptEvaluator>? = null

    /*
     * Tries to evaluate JS using existing evaluator. If it is broken, tears it down and creates
     * tries to execute it again, falling back to a WebView if that fails.
     * */
    override suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome =
        try {
            // Try to evaluate with the existing evaluator
            createEvaluatorIfDoesntExist().evaluate(base64params, rule)
        } catch (throwable: SandboxDeadException) {
            // If evaluation failed, try teardown and recreate evaluator
            teardown()
            tryEvaluateWithFallback(base64params, rule)
        } catch (e: Exception) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.superwallCore,
                message = "Failed to evaluate rule with fallback: ${e.message}",
            )
            TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)
        }

    override fun teardown() {
        runBlocking {
            try {
                eval?.await()?.teardown()
            } catch (e: Exception) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message = "Failed to teardown evaluator: ${e.message}",
                )
            }
            // Clear the existing evaluator and try with fallback to webview
            eval = null
        }
    }

    private suspend fun createNewEvaluator(context: Context): JavascriptEvaluator =
        when {
            JavaScriptSandbox.isSupported() -> createSandboxEvaluator(context)
            webViewExists() -> createWebViewEvaluator(context)
            else -> NoSupportedEvaluator
        }

    private suspend fun createSandboxEvaluator(context: Context): JavascriptEvaluator =
        createSandbox(context)
            .fold(onSuccess = {
                SandboxJavascriptEvaluator(it, ioScope, storage)
            }, onFailure = {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message = "Failed to create javascript sandbox evaluator: ${it.message}",
                )
                createWebViewEvaluator(context) // Fallback to WebView
            })

    private suspend fun createWebViewEvaluator(context: Context): JavascriptEvaluator =
        uiScope
            .async {
                WebviewJavascriptEvaluator(WebView(context), uiScope, storage)
            }.await()

    // Tries to create a JSSandbox and if it fails, it falls back to a WebView
    private suspend fun tryEvaluateWithFallback(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome =
        try {
            createEvaluatorIfDoesntExist().evaluate(base64params, rule)
        } catch (e: Exception) {
            teardown()
            createEvaluatorIfDoesntExist {
                createWebViewEvaluator(context)
            }.evaluate(base64params, rule)
        }

    private suspend fun createEvaluatorIfDoesntExist(
        invoke: suspend () -> JavascriptEvaluator = {
            createNewEvaluator(context)
        },
    ): JavascriptEvaluator {
        mutex.lock()
        val current = eval
        val evaluator =
            if (current == null) {
                val call =
                    ioScope.async {
                        invoke()
                    }
                eval = call
                call.await()
            } else {
                current.await()
            }
        mutex.unlock()
        return evaluator
    }
}
