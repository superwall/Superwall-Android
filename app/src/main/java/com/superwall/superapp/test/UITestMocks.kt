package com.superwall.superapp.test

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.delegate.PaywallViewDelegate

@Deprecated("Will be removed in the upcoming versions, use MockPaywallViewDelegate instead")
typealias MockPaywallViewControllerDelegate = MockPaywallViewDelegate
class MockPaywallViewDelegate : PaywallViewDelegate {
    private var paywallViewDidFinish: ((PaywallView, PaywallResult, Boolean) -> Unit)? = null

    override fun didFinish(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) {
        paywallViewDidFinish?.invoke(paywall, result, shouldDismiss)
        if (shouldDismiss) {
            paywall.encapsulatingActivity?.finish()
        }
    }

    @Deprecated("Will be removed in the upcoming versions, use paywallViewDidFinish instead")
    fun paywallViewControllerDidFinish(handler: (PaywallView, PaywallResult, Boolean) -> Unit) {
        paywallViewDidFinish(handler)
    }

    fun paywallViewDidFinish(handler: (PaywallView, PaywallResult, Boolean) -> Unit) {
        paywallViewDidFinish = handler
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
