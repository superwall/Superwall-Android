package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule

object NoSupportedEvaluator : JavascriptEvaluator {
    override suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome {
        Logger.debug(
            LogLevel.warn,
            LogScope.jsEvaluator,
            "Javascript sandbox and Webview are unsupported, nothing was evaluated.",
        )
        return TriggerRuleOutcome.noMatch(
            UnmatchedRule.Source.EXPRESSION,
            rule.experiment.id,
        )
    }

    override fun teardown() {}
}
