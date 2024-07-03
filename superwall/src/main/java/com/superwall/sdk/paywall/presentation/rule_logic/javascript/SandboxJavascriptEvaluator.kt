package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import androidx.javascriptengine.JavaScriptSandbox
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.SDKJS
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.guava.await

internal class SandboxJavascriptEvaluator(
    val jsSandbox: JavaScriptSandbox,
    val factory: RuleAttributesFactory,
    val storage: Storage,
) : JavascriptEvaluator {
    override suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome {
        val jsIsolate = jsSandbox?.createIsolate()
        jsIsolate?.addOnTerminatedCallback {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.superwallCore,
                message = "$it",
            )
        }

        val resultFuture = jsIsolate?.evaluateJavaScriptAsync("$SDKJS\n $base64params")

        val result = resultFuture?.await()
        jsIsolate?.close()

        if (result.isNullOrEmpty()) {
            return TriggerRuleOutcome.noMatch(UnmatchedRule.Source.EXPRESSION, rule.experiment.id)
        } else {
            val expressionMatched = result == "true"
            return rule.tryToMatchOccurrence(storage, expressionMatched)
        }
    }
}
