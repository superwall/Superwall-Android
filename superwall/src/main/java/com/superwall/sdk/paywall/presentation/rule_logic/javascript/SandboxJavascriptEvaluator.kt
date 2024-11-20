package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import androidx.javascriptengine.JavaScriptSandbox
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.SDKJS
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.core_data.CoreDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class SandboxJavascriptEvaluator(
    private val jsSandbox: JavaScriptSandbox,
    private val ioScope: CoroutineScope,
    private val storage: CoreDataManager,
) : JavascriptEvaluator {
    override suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome =
        withContext(ioScope.coroutineContext) {
            val jsIsolate = jsSandbox.createIsolate()
            jsIsolate.addOnTerminatedCallback {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message = "$it",
                )
            }

            val resultFuture = jsIsolate.evaluateJavaScriptAsync("$SDKJS\n $base64params")

            val result = resultFuture.await()
            jsIsolate.close()

            if (result.isNullOrEmpty()) {
                TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)
            } else {
                val expressionMatched = result == "true"
                rule.tryToMatchOccurrence(storage, expressionMatched)
            }
        }

    override fun teardown() {
        runBlocking {
            jsSandbox.close()
        }
    }
}
