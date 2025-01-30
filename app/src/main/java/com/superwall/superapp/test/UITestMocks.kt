package com.superwall.superapp.test

import com.superwall.sdk.analytics.superwall.SuperwallPlacementInfo
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallViewCallback

class MockPaywallViewDelegate : PaywallViewCallback {
    private var paywallViewFinished: ((PaywallView, PaywallResult, Boolean) -> Unit)? = null

    override fun onFinished(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) {
        paywallViewFinished?.invoke(paywall, result, shouldDismiss)
        if (shouldDismiss) {
            paywall.encapsulatingActivity?.get()?.finish()
        }
    }

    fun paywallViewFinished(handler: (PaywallView, PaywallResult, Boolean) -> Unit) {
        paywallViewFinished = handler
    }
}

class MockSuperwallDelegate : SuperwallDelegate {
    private var handleSuperwallEvent: ((SuperwallPlacementInfo) -> Unit)? = null

    fun handleSuperwallEvent(handler: (SuperwallPlacementInfo) -> Unit) {
        handleSuperwallEvent = handler
    }

    override fun handleSuperwallPlacement(withInfo: SuperwallPlacementInfo) {
        handleSuperwallEvent?.invoke(withInfo)
    }
}
