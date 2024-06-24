package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaywallViewDelegateAdapter(
    val kotlinDelegate: PaywallViewCallback?,
) {
    val hasJavaDelegate: Boolean
        get() = false

    @Deprecated("Will be removed in the upcoming versions, use onFinished instead")
    suspend fun didFinish(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) = onFinished(paywall, result, shouldDismiss)

    suspend fun onFinished(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) = withContext(Dispatchers.Main) {
        kotlinDelegate?.onFinished(paywall, result, shouldDismiss)
    }

}

@Deprecated("Will be removed in the upcoming versions, use PaywallViewDelegateAdapter instead")
typealias PaywallViewControllerDelegateAdapter = PaywallViewDelegateAdapter

