package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent

interface PaywallViewControllerDelegate {
    // TODO: missing `shouldDismiss`
    fun didFinish(
        paywall: PaywallViewController,
        result: PaywallResult,
        shouldDismiss: Boolean,
    )
}

interface PaywallViewControllerEventDelegate {
    suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallViewController: PaywallViewController,
    )
}

sealed class PaywallLoadingState {
    class Unknown : PaywallLoadingState()

    class LoadingPurchase : PaywallLoadingState()

    class LoadingURL : PaywallLoadingState()

    class ManualLoading : PaywallLoadingState()

    class Ready : PaywallLoadingState()
}
