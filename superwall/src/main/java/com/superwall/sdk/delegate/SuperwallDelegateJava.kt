package com.superwall.sdk.delegate

import android.net.Uri
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URI

interface SuperwallDelegateJava {
    fun handleCustomPaywallAction(name: String) {}

    fun willDismissPaywall(paywallInfo: PaywallInfo) {}

    fun willPresentPaywall(paywallInfo: PaywallInfo) {}

    fun didDismissPaywall(paywallInfo: PaywallInfo) {}

    fun didPresentPaywall(paywallInfo: PaywallInfo) {}

    fun paywallWillOpenURL(url: URI) {}

    fun paywallWillOpenDeepLink(url: Uri) {}

    fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {}

    fun subscriptionStatusDidChange(newValue: SubscriptionStatus) {}

    fun handleLog(
        level: String,
        scope: String,
        message: String? = null,
        info: Map<String, Any>? = null,
        error: Throwable? = null,
    ) {
    }
}
