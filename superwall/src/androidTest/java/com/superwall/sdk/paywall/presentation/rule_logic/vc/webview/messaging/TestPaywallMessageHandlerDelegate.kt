package com.superwall.sdk.paywall.presentation.rule_logic.vc.webview.messaging

import android.webkit.WebView
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandlerDelegate
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent

class TestPaywallMessageHandlerDelegate(
    override val request: PresentationRequest? = null,
    override var paywall: Paywall = Paywall.stub(),
    override val info: PaywallInfo = Paywall.stub().getInfo(EventData.stub()),
    override val webView: WebView,
    override var loadingState: PaywallLoadingState = PaywallLoadingState.Unknown(),
    override val isActive: Boolean = true,
    val onEvent: (PaywallWebEvent) -> Unit = {},
) : PaywallMessageHandlerDelegate {
    override fun eventDidOccur(paywallWebEvent: PaywallWebEvent) {
        onEvent(paywallWebEvent)
    }

    override fun openDeepLink(url: String) {
        TODO("Not yet implemented")
    }

    override fun presentBrowserInApp(url: String) {
        TODO("Not yet implemented")
    }

    override fun presentBrowserExternal(url: String) {
        TODO("Not yet implemented")
    }
}
