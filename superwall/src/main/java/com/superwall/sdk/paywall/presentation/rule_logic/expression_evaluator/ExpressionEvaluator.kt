package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.runOnUiThread
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

interface ExpressionEvaluating {
    suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?
    ): TriggerRuleOutcome
}

class ExpressionEvaluator(
    private val context: Context,
    private val storage: Storage,
    private val factory: RuleAttributesFactory
): ExpressionEvaluating {

    companion object {
        public var sharedWebView: WebView? = null
    }

    init {
        // Setup the sharedWebView if it's not already setup
        if (sharedWebView == null) {
            runOnUiThread {
                sharedWebView = WebView(context)
                sharedWebView!!.settings.javaScriptEnabled = true
                sharedWebView!!.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        println("!!JS Console: ${consoleMessage.message()}")
                        return true
                    }
                }
            }
        }
    }

    override suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?
    ): TriggerRuleOutcome {
        // Expression matches all
        if (rule.expressionJs == null && rule.expression == null) {
            return tryToMatchOccurrence(rule, true)
        }

        val base64Params = getBase64Params(rule, eventData) ?: return TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)

        var deferred: CompletableDeferred<TriggerRuleOutcome> = CompletableDeferred()
        runOnUiThread {
            sharedWebView!!.evaluateJavascript(
                SDKJS + "\n " + base64Params,
                ValueCallback<String?> { result ->
                    println("!! evaluateJavascript result: $result")

                    if (result == null) {
                        deferred.complete(TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id))
                    } else {
                        val expressionMatched = result == "true"

                        CoroutineScope(Dispatchers.IO).launch {
                            val ruleMatched = tryToMatchOccurrence(rule, expressionMatched)
                            deferred.complete(ruleMatched)
                        }
                    }
                })
        }

        return deferred.await()
    }

    private suspend fun getBase64Params(
        rule: TriggerRule,
        eventData: EventData?
    ): String? {
        val jsonAttributes = factory.makeRuleAttributes(eventData, rule.computedPropertyRequests)

        rule.expressionJs?.let { expressionJs ->
            JavascriptExpressionEvaluatorParams(expressionJs, JSONObject(jsonAttributes)).toBase64Input()?.let { base64Params ->
                return "\n SuperwallSDKJS.evaluateJS64('$base64Params');"
            }
        }

        rule.expression?.let { expression ->
            LiquidExpressionEvaluatorParams(expression, JSONObject(jsonAttributes)).toBase64Input()?.let { base64Params ->
                return "\n SuperwallSDKJS.evaluate64('$base64Params');"
            }
        }

        return null
    }

    suspend fun tryToMatchOccurrence(
        rule: TriggerRule,
        expressionMatched: Boolean
    ): TriggerRuleOutcome {
        if (expressionMatched) {
            rule.occurrence?.let { occurrence ->
                val count = storage.coreDataManager.countTriggerRuleOccurrences(occurrence) + 1
                val shouldFire = count <= occurrence.maxCount

                if (shouldFire) {
                    return TriggerRuleOutcome.match(rule, occurrence)
                } else {
                    return TriggerRuleOutcome.noMatch(
                        UnmatchedRule.Source.OCCURRENCE,
                        rule.experiment.id
                    )
                }
            } ?: run {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallPresentation,
                    message = "No occurrence parameter found for trigger rule."
                )
                return TriggerRuleOutcome.match(rule)
            }
        }
        return TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)
    }
}
