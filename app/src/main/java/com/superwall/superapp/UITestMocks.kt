package com.superwall.superapp

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate

class MockPaywallViewControllerDelegate : PaywallViewControllerDelegate {
    private var paywallViewControllerDidFinish: ((PaywallViewController, PaywallResult, Boolean) -> Unit)? = null

    override fun didFinish(
        paywall: PaywallViewController,
        result: PaywallResult,
        shouldDismiss: Boolean
    ) {
        paywallViewControllerDidFinish?.invoke(paywall, result, shouldDismiss)
    }
    fun paywallViewControllerDidFinish(handler: (PaywallViewController, PaywallResult, Boolean) -> Unit) {
        paywallViewControllerDidFinish = handler
    }
}
