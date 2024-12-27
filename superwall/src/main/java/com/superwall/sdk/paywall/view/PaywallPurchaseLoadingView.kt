package com.superwall.sdk.paywall.view

import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState

interface PaywallPurchaseLoadingView {
    fun setupFor(
        paywallView: PaywallView,
        loadingState: PaywallLoadingState,
    )
}
