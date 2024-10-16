package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.SDKJS
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.core_data.CoreDataManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

internal class WebviewJavascriptEvaluator(
    private val webView: WebView,
    private val mainScope: CoroutineScope,
    private val storage: CoreDataManager,
) : JavascriptEvaluator {
    init {
        webView.settings.javaScriptEnabled = true
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
            }
    }

    override suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome {
        val deferred: CompletableDeferred<TriggerRuleOutcome> = CompletableDeferred()
        mainScope.async {
            webView!!.evaluateJavascript(
                "$SDKJS\n $base64params",
            ) { result ->
                Logger.debug(LogLevel.debug, LogScope.jsEvaluator, "!! evaluateJavascript result: $result")

                if (result == null) {
                    deferred.complete(
                        TriggerRuleOutcome.noMatch(
                            UnmatchedRule.Source.EXPRESSION,
                            rule.experiment.id,
                        ),
                    )
                } else {
                    val expressionMatched = result.replace("\"", "") == "true"

                    CoroutineScope(Dispatchers.IO).launch {
                        val ruleMatched = rule.tryToMatchOccurrence(storage, expressionMatched)
                        deferred.complete(ruleMatched)
                    }
                }
            }
        }

        return deferred.await()
    }

    override fun teardown() {
        webView.destroy()
    }
}
