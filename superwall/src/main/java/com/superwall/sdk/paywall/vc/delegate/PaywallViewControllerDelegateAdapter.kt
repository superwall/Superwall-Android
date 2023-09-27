package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaywallViewControllerDelegateAdapter(
    val kotlinDelegate: PaywallViewControllerDelegate?
) {
    val hasJavaDelegate: Boolean
        get() = false

    suspend fun didFinish(
        paywall: PaywallViewController,
        result: PaywallResult,
        shouldDismiss: Boolean
    ) = withContext(Dispatchers.Main) {
        kotlinDelegate?.didFinish(paywall, result, shouldDismiss)
    }
}
