package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import LogLevel
import LogScope
import Logger
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.misc.runOnUiThread
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

class ExpressionEvaluator(
    private val context: Context,
    private val storage: Storage,
    private val factory: RuleAttributesFactory
) {

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

    suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData,
        isPreemptive: Boolean
    ): Boolean {
        // Expression matches all
        if (rule.expressionJs == null && rule.expression == null) {
            val shouldFire = shouldFire(rule.occurrence, ruleMatched = true, isPreemptive)
            return shouldFire
        }


//        jsCtx.exceptionHandler = { _, value ->
//            val stackTraceString = value?.getProperty("stack")?.toString()
//            val lineNumber = value?.getProperty("line")
//            val columnNumber = value?.getProperty("column")
//            val moreInfo = "In method $stackTraceString, Line number in file: $lineNumber, column: $columnNumber"
//            Logger.debug(
//                logLevel = LogLevel.error,
//                scope = LogScope.events,
//                message = "JS ERROR: $value $moreInfo",
//                info = null,
//                error = null
//            )
//        }

        val postfix = getPostfix(rule, eventData)

        println("postfix: $postfix")

//        println("!! SDKJS: $SDKJS")


        var deffered: CompletableDeferred<Boolean> = CompletableDeferred()
        runOnUiThread {
            sharedWebView!!.evaluateJavascript(
                SDKJS + "\n " + postfix,
                ValueCallback<String?> { result ->
                    println("!! evaluateJavascript result: $result")

                    val isMatched = result?.toString() == "true"
                    val shouldFire = shouldFire(rule.occurrence, isMatched, isPreemptive)
                    deffered.complete(shouldFire)
                })
        }

        return deffered.await()
    }

    private suspend fun getPostfix(rule: TriggerRule, eventData: EventData): String? {
        val jsonAttributes = factory.makeRuleAttributes(event = eventData, computedPropertyRequests = rule.computedPropertyRequests)

        return when {
            rule.expressionJs != null -> {
                val base64Params = JavascriptExpressionEvaluatorParams(
                    expressionJs = rule.expressionJs,
                    values = jsonAttributes
                ).toBase64Input()


                base64Params?.let { "\n SuperwallSDKJS.evaluateJS64('$it');" }
            }
            rule.expression != null -> {
                val base64Params = LiquidExpressionEvaluatorParams(
                    expression = rule.expression,
                    values = jsonAttributes
                ).toBase64Input()

                base64Params?.let { "\n SuperwallSDKJS.evaluate64('$it');" }
            }
            else -> null
        }
    }

    fun shouldFire(
        occurrence: TriggerRuleOccurrence?,
        ruleMatched: Boolean,
        isPreemptive: Boolean
    ): Boolean {
        if (ruleMatched) {
            if (occurrence == null) {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallPresentation,
                    message = "No occurrence parameter found for trigger rule."
                )
                return true
            }
            val count = storage.coreDataManager.countTriggerRuleOccurrences(occurrence) + 1
            val shouldFire = count <= occurrence.maxCount

            if (shouldFire && !isPreemptive) {
                storage.coreDataManager.save(triggerRuleOccurrence = occurrence, completion = null)
            }

            return shouldFire
        }

        return false
    }
}
