package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaywallViewDelegateAdapter(
    val kotlinDelegate: PaywallViewDelegate?,
) {
    val hasJavaDelegate: Boolean
        get() = false

    suspend fun didFinish(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) = withContext(Dispatchers.Main) {
        kotlinDelegate?.didFinish(paywall, result, shouldDismiss)
    }
}

@Deprecated("Will be removed in the upcoming versions, use PaywallViewDelegateAdapter instead")
typealias PaywallViewControllerDelegateAdapter = PaywallViewDelegateAdapter

