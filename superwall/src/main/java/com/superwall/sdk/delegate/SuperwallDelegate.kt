package com.superwall.sdk.delegate

import android.net.Uri
import com.superwall.sdk.analytics.superwall.SuperwallPlacementInfo
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URI

interface SuperwallDelegate {
    fun subscriptionStatusDidChange(
        from: SubscriptionStatus,
        to: SubscriptionStatus,
    ) {}

    fun handleSuperwallPlacement(eventInfo: SuperwallPlacementInfo) {}

    fun handleCustomPaywallAction(withName: String) {}

    fun willDismissPaywall(withInfo: PaywallInfo) {}

    fun willPresentPaywall(withInfo: PaywallInfo) {}

    fun didDismissPaywall(withInfo: PaywallInfo) {}

    fun didPresentPaywall(withInfo: PaywallInfo) {}

    fun paywallWillOpenURL(url: URI) {}

    fun paywallWillOpenDeepLink(url: Uri) {}

    fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?,
    ) {}
}
