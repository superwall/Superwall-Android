
package com.superwall.sdk.paywall.vc.delegate
import com.superwall.sdk.paywall.vc.PaywallViewController

class PaywallViewControllerDelegateAdapter(
    var swiftDelegate: PaywallViewControllerDelegate?,
) {
    val hasObjcDelegate: Boolean
        get() = false

    suspend fun didFinish(controller: PaywallViewController, swiftResult: PaywallResult) {
        swiftDelegate?.paywallViewController(controller, swiftResult)
    }
}
