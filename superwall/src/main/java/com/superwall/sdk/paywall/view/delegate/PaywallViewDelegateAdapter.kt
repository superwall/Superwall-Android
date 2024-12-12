package com.superwall.sdk.paywall.view.delegate

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaywallViewDelegateAdapter(
    val kotlinDelegate: PaywallViewCallback?,
) {
    val hasJavaDelegate: Boolean
        get() = false

    suspend fun onFinished(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) = withContext(Dispatchers.Main) {
        kotlinDelegate?.onFinished(paywall, result, shouldDismiss)
    }
}
