package com.superwall.sdk.delegate

import android.net.Uri
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URI

class SuperwallDelegateAdapter {
    var kotlinDelegate: SuperwallDelegate? = null
    var javaDelegate: SuperwallDelegateJava? = null

    fun handleCustomPaywallAction(name: String) {
        kotlinDelegate?.handleCustomPaywallAction(name)
            ?: javaDelegate?.handleCustomPaywallAction(name)
    }

    fun willDismissPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.willDismissPaywall(paywallInfo)
            ?: javaDelegate?.willDismissPaywall(paywallInfo)
    }

    fun didDismissPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.didDismissPaywall(paywallInfo)
            ?: javaDelegate?.didDismissPaywall(paywallInfo)
    }

    fun willPresentPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.willPresentPaywall(paywallInfo)
            ?: javaDelegate?.willPresentPaywall(paywallInfo)
    }

    fun didPresentPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.didPresentPaywall(paywallInfo)
            ?: javaDelegate?.didPresentPaywall(paywallInfo)
    }

    fun paywallWillOpenURL(url: URI) {
        kotlinDelegate?.paywallWillOpenURL(url)
            ?: javaDelegate?.paywallWillOpenURL(url)
    }

    fun paywallWillOpenDeepLink(url: Uri) {
        kotlinDelegate?.paywallWillOpenDeepLink(url)
            ?: javaDelegate?.paywallWillOpenDeepLink(url)
    }

    fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
        kotlinDelegate?.handleSuperwallEvent(eventInfo)
            ?: javaDelegate?.handleSuperwallEvent(eventInfo)
    }

    fun entitlementStatusDidChange(newValue: SubscriptionStatus) {
        kotlinDelegate?.subscriptionStatusDidChange(newValue)
            ?: javaDelegate?.entitlementStatusDidChange(newValue)
    }

    fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?,
    ) {
        kotlinDelegate?.handleLog(
            level = level,
            scope = scope,
            message = message,
            info = info,
            error = error,
        ) ?: javaDelegate?.handleLog(
            level = level,
            scope = scope,
            message = message,
            info = info,
            error = error,
        )
    }
}
