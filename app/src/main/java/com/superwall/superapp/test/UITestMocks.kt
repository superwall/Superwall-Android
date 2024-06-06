package com.superwall.superapp.test

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate

class MockPaywallViewControllerDelegate : PaywallViewControllerDelegate {
    private var paywallViewControllerDidFinish: ((PaywallViewController, PaywallResult, Boolean) -> Unit)? = null

    override fun didFinish(
        paywall: PaywallViewController,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) {
        paywallViewControllerDidFinish?.invoke(paywall, result, shouldDismiss)
        if (shouldDismiss) {
            paywall.encapsulatingActivity?.finish()
        }
    }

    fun paywallViewControllerDidFinish(handler: (PaywallViewController, PaywallResult, Boolean) -> Unit) {
        paywallViewControllerDidFinish = handler
    }
}

class MockSuperwallDelegate : SuperwallDelegate {
    private var handleSuperwallEvent: ((SuperwallEventInfo) -> Unit)? = null

    fun handleSuperwallEvent(handler: (SuperwallEventInfo) -> Unit) {
        handleSuperwallEvent = handler
    }

    override fun handleSuperwallEvent(withInfo: SuperwallEventInfo) {
        handleSuperwallEvent?.invoke(withInfo)
    }
}
