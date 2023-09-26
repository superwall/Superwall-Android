package com.superwall.superapp

import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallResult
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate

class MockPaywallViewControllerDelegate : PaywallViewControllerDelegate {

    private var paywallViewControllerDidFinish: ((PaywallViewController, PaywallResult, Boolean) -> Unit)? = null

    fun paywallViewControllerDidFinish(handler: (PaywallViewController, PaywallResult, Boolean) -> Unit) {
        paywallViewControllerDidFinish = handler
    }

    override fun paywallViewController(controller: PaywallViewController, didFinishWith: PaywallResult) {
        // TODO: missing should dismiss
        paywallViewControllerDidFinish?.invoke(controller, didFinishWith, true)
    }
}
