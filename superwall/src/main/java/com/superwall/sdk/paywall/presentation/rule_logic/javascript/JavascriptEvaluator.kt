package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import android.content.Context
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome

interface JavascriptEvaluator {
    suspend fun evaluate(
        base64params: String,
        rule: TriggerRule,
    ): TriggerRuleOutcome

    fun teardown()

    fun interface Factory {
        suspend fun provideJavascriptEvaluator(context: Context): JavascriptEvaluator
    }
}
