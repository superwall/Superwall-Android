package com.superwall.sdk.paywall.presentation.get_paywall

import android.app.Activity
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallViewController

data class PaywallComponents(
    val viewController: PaywallViewController,
    val presenter: Activity?,
    val rulesOutcome: RuleEvaluationOutcome,
    val debugInfo: Map<String, Any>

    // TODO: class impl
)