package com.superwall.sdk.delegate

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URL

interface SuperwallDelegate {
    fun subscriptionStatusDidChange(to: SubscriptionStatus)
    suspend fun handleSuperwallEvent(withInfo: SuperwallEventInfo)
    suspend fun handleCustomPaywallAction(withName: String)
    suspend fun willDismissPaywall(withInfo: PaywallInfo)
    suspend fun willPresentPaywall(withInfo: PaywallInfo)
    suspend fun didDismissPaywall(withInfo: PaywallInfo)
    suspend fun didPresentPaywall(withInfo: PaywallInfo)
    suspend fun paywallWillOpenURL(url: URL)
    suspend fun paywallWillOpenDeepLink(url: URL)
    suspend fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?
    )
}

// Default implementations for all SuperwallDelegate methods
open class DefaultSuperwallDelegate : SuperwallDelegate {
    override fun subscriptionStatusDidChange(to: SubscriptionStatus) {}
    override suspend fun handleSuperwallEvent(withInfo: SuperwallEventInfo) {}
    override suspend fun handleCustomPaywallAction(withName: String) {}
    override suspend fun willDismissPaywall(withInfo: PaywallInfo) {}
    override suspend fun willPresentPaywall(withInfo: PaywallInfo) {}
    override suspend fun didDismissPaywall(withInfo: PaywallInfo) {}
    override suspend fun didPresentPaywall(withInfo: PaywallInfo) {}
    override suspend fun paywallWillOpenURL(url: URL) {}
    override suspend fun paywallWillOpenDeepLink(url: URL) {}
    override suspend fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?
    ) {
    }
}
