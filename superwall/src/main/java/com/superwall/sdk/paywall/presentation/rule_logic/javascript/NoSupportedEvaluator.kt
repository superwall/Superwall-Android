package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import android.util.Log
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule

object NoSupportedEvaluator : JavascriptEvaluator {
    override suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome {
        Log.e(LogLevel.warn.toString(), "Javascript sandbox and Webview are unsupported, nothing was evaluated.")
        return TriggerRuleOutcome.noMatch(
            UnmatchedRule.Source.EXPRESSION,
            rule.experiment.id,
        )
    }

    override fun teardown() {}
}
