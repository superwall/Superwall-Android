package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent

sealed class PaywallResult {
    object Unknown : PaywallResult()
    object LoadingPurchase : PaywallResult()
    object LoadingURL : PaywallResult()
    object ManualLoading : PaywallResult()
    object Ready : PaywallResult()
}

interface PaywallViewControllerDelegate {
    fun paywallViewController(controller: PaywallViewController, didFinishWith: PaywallResult)
}

interface PaywallViewControllerEventDelegate {
    suspend fun eventDidOccur(paywallEvent: PaywallWebEvent, on: PaywallViewController)
}
