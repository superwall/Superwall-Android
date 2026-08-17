package com.superwall.sdk.delegate

import android.net.Uri
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import java.net.URI

class SuperwallDelegateAdapter {
    companion object {
        /**
         * Mirrors "either delegate is set" so that the logger can decide whether a log is worth
         * building without resolving the dependency container on every one of its call sites.
         */
        @Volatile
        internal var hasAnyDelegate: Boolean = false
            private set
    }

    init {
        // A freshly constructed adapter holds no delegates yet, which keeps the flag correct
        // across teardown/configure cycles.
        hasAnyDelegate = false
    }

    var kotlinDelegate: SuperwallDelegate? = null
        set(value) {
            field = value
            hasAnyDelegate = value != null || javaDelegate != null
        }

    var javaDelegate: SuperwallDelegateJava? = null
        set(value) {
            field = value
            hasAnyDelegate = value != null || kotlinDelegate != null
        }

    fun handleCustomPaywallAction(name: String) {
        kotlinDelegate?.handleCustomPaywallAction(name)
            ?: javaDelegate?.handleCustomPaywallAction(name)
    }

    fun didRedeemLink(result: RedemptionResult) {
        kotlinDelegate?.didRedeemLink(result)
            ?: javaDelegate?.didRedeemLink(result)
    }

    fun willRedeemLink() {
        kotlinDelegate?.willRedeemLink()
            ?: javaDelegate?.willRedeemLink()
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
        // Calling this until we deprecate it
        kotlinDelegate?.handleSuperwallEvent(eventInfo)
            ?: javaDelegate?.handleSuperwallEvent(eventInfo)
    }

    fun subscriptionStatusDidChange(
        from: com.superwall.sdk.models.entitlements.SubscriptionStatus,
        to: com.superwall.sdk.models.entitlements.SubscriptionStatus,
    ) {
        kotlinDelegate?.subscriptionStatusDidChange(from, to)
            ?: javaDelegate?.subscriptionStatusDidChange(from, to)
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

    fun userAttributesDidChange(newAttributes: Map<String, Any>) {
        kotlinDelegate?.userAttributesDidChange(newAttributes)
            ?: javaDelegate?.userAttributesDidChange(newAttributes)
    }

    fun customerInfoDidChange(
        from: CustomerInfo,
        to: CustomerInfo,
    ) {
        kotlinDelegate?.customerInfoDidChange(from, to) ?: javaDelegate?.customerInfoDidChange(
            from,
            to,
        )
    }
}
