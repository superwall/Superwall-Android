package com.superwall.sdk.paywall.vc.delegate

import androidx.annotation.MainThread
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController

class PaywallViewControllerDelegateAdapter(
    val kotlinDelegate: PaywallViewControllerDelegate?
) {
    val hasJavaDelegate: Boolean
        get() = false

    @MainThread
    suspend fun didFinish(
        paywall: PaywallViewController,
        result: PaywallResult,
        shouldDismiss: Boolean
    ) {

        kotlinDelegate?.didFinish(paywall, result, shouldDismiss)
    }
}
