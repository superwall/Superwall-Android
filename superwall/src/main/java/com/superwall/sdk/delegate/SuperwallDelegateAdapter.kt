package com.superwall.sdk.delegate

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URL

class SuperwallDelegateAdapter {
    var kotlinDelegate: SuperwallDelegate? = null
    var javaDelegate: SuperwallDelegateJava? = null

    suspend fun handleCustomPaywallAction(name: String) {
        kotlinDelegate?.handleCustomPaywallAction(name)
            ?: javaDelegate?.handleCustomPaywallAction(name)
    }

    private suspend fun willDismissPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.willDismissPaywall(paywallInfo)
            ?: javaDelegate?.willDismissPaywall(paywallInfo)
    }

    private suspend fun willPresentPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.willPresentPaywall(paywallInfo)
            ?: javaDelegate?.willPresentPaywall(paywallInfo)
    }

    private suspend fun didDismissPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.didDismissPaywall(paywallInfo)
            ?: javaDelegate?.didDismissPaywall(paywallInfo)
    }

    private suspend fun didPresentPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.didPresentPaywall(paywallInfo)
            ?: javaDelegate?.didPresentPaywall(paywallInfo)
    }

    suspend fun paywallWillOpenURL(url: URL) {
        kotlinDelegate?.paywallWillOpenURL(url)
            ?: javaDelegate?.paywallWillOpenURL(url)
    }

    suspend fun paywallWillOpenDeepLink(url: URL) {
        kotlinDelegate?.paywallWillOpenDeepLink(url)
            ?: javaDelegate?.paywallWillOpenDeepLink(url)
    }

    suspend fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
        kotlinDelegate?.handleSuperwallEvent(eventInfo)
            ?: javaDelegate?.handleSuperwallEvent(eventInfo)
    }

    fun subscriptionStatusDidChange(newValue: SubscriptionStatus) {
        kotlinDelegate?.subscriptionStatusDidChange(newValue)
            ?: javaDelegate?.subscriptionStatusDidChange(newValue)
    }

    private suspend fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?
    ) {
        Logger.debug(
            logLevel = LogLevel.valueOf(level),
            scope = LogScope.valueOf(scope),
            message = message ?: "No message",
            info = info,
            error = error
        )
    }
}
