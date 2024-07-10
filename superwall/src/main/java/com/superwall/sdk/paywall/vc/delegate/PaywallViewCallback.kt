package com.superwall.sdk.paywall.vc.delegate

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent

@Deprecated("Will be removed in the upcoming versions, use PaywallViewDelegate instead")
typealias PaywallViewControllerDelegate = PaywallViewCallback

@Deprecated("Will be removed in the upcoming versions, use PaywallViewEventDelegate instead")
typealias PaywallViewControllerEventDelegate = PaywallViewEventCallback

interface PaywallViewCallback {
    // TODO: missing `shouldDismiss`

    @Deprecated("Will be removed in the upcoming versions, use onFinish instead")
    fun didFinish(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    ) = onFinished(paywall, result, shouldDismiss)

    fun onFinished(
        paywall: PaywallView,
        result: PaywallResult,
        shouldDismiss: Boolean,
    )
}

fun interface PaywallViewEventCallback {
    suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallView: PaywallView,
    )
}

sealed class PaywallLoadingState {
    class Unknown : PaywallLoadingState()

    class LoadingPurchase : PaywallLoadingState()

    class LoadingURL : PaywallLoadingState()

    class ManualLoading : PaywallLoadingState()

    class Ready : PaywallLoadingState()
}
