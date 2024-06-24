package com.superwall.superapp.test

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.delegate.PaywallViewCallback

@Deprecated("Will be removed in the upcoming versions, use MockPaywallViewDelegate instead")
typealias MockPaywallViewControllerDelegate = MockPaywallViewDelegate

class MockPaywallViewDelegate : PaywallViewCallback {
    private var paywallViewFinished: ((PaywallView, PaywallResult, Boolean) -> Unit)? = null

    override fun didFinish(paywall: PaywallView, result: PaywallResult, shouldDismiss: Boolean) =
        onFinished(paywall, result, shouldDismiss)

    override fun onFinished(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) {
        paywallViewFinished?.invoke(paywall, result, shouldDismiss)
        if (shouldDismiss) {
            paywall.encapsulatingActivity?.finish()
        }
    }

    @Deprecated("Will be removed in the upcoming versions, use paywallViewFinished instead")
    fun paywallViewControllerDidFinish(handler: (PaywallView, PaywallResult, Boolean) -> Unit) {
        paywallViewFinished(handler)
    }

    fun paywallViewFinished(handler: (PaywallView, PaywallResult, Boolean) -> Unit) {
        paywallViewFinished = handler
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
