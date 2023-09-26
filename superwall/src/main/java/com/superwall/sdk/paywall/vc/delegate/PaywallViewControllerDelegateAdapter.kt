package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.vc.PaywallViewController

class PaywallViewControllerDelegateAdapter(
    var kotlinDelegate: PaywallViewControllerDelegate?,
) {
    val hasJavaDelegate: Boolean
        get() = false

    suspend fun didFinish(controller: PaywallViewController, swiftResult: PaywallResult) {
        kotlinDelegate?.paywallViewController(controller, swiftResult)
    }
}
