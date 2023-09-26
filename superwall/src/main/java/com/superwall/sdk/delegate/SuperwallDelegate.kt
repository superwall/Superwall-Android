package com.superwall.sdk.delegate

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URL

interface SuperwallDelegate {
    fun subscriptionStatusDidChange(to: SubscriptionStatus) {}
    fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {}
    fun handleCustomPaywallAction(withName: String) {}
    fun willDismissPaywall(withInfo: PaywallInfo) {}
    fun willPresentPaywall(withInfo: PaywallInfo) {}
    fun didDismissPaywall(withInfo: PaywallInfo) {}
    fun didPresentPaywall(withInfo: PaywallInfo) {}
    fun paywallWillOpenURL(url: URL) {}
    fun paywallWillOpenDeepLink(url: URL) {}
    fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?
    ) {}
}