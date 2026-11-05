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

    private inline fun dispatch(
        callbackName: String,
        kotlinCallback: (SuperwallDelegate) -> Unit,
        javaCallback: (SuperwallDelegateJava) -> Unit,
    ) {
        kotlinDelegate?.let { delegate ->
            invokeSafely(callbackName) { kotlinCallback(delegate) }
            return
        }

        javaDelegate?.let { delegate ->
            invokeSafely(callbackName) { javaCallback(delegate) }
        }
    }

    private inline fun invokeSafely(
        callbackName: String,
        callback: () -> Unit,
    ) {
        try {
            callback()
        } catch (error: LinkageError) {
            reportDelegateFailure(callbackName, error)
        } catch (exception: Exception) {
            reportDelegateFailure(callbackName, exception)
        }
    }

    /**
     * Delegate failures cannot be reported through [com.superwall.sdk.logger.Logger], because a
     * failing `handleLog` implementation would recursively call the same delegate.
     */
    private fun reportDelegateFailure(
        callbackName: String,
        throwable: Throwable,
    ) {
        System.err.println(
            "[!!Superwall] Delegate callback $callbackName failed: " +
                "${throwable.javaClass.name}: ${throwable.localizedMessage}",
        )
    }

    fun handleCustomPaywallAction(name: String) {
        dispatch(
            callbackName = "handleCustomPaywallAction",
            kotlinCallback = { it.handleCustomPaywallAction(name) },
            javaCallback = { it.handleCustomPaywallAction(name) },
        )
    }

    fun didRedeemLink(result: RedemptionResult) {
        dispatch(
            callbackName = "didRedeemLink",
            kotlinCallback = { it.didRedeemLink(result) },
            javaCallback = { it.didRedeemLink(result) },
        )
    }

    fun willRedeemLink() {
        dispatch(
            callbackName = "willRedeemLink",
            kotlinCallback = { it.willRedeemLink() },
            javaCallback = { it.willRedeemLink() },
        )
    }

    fun willDismissPaywall(paywallInfo: PaywallInfo) {
        dispatch(
            callbackName = "willDismissPaywall",
            kotlinCallback = { it.willDismissPaywall(paywallInfo) },
            javaCallback = { it.willDismissPaywall(paywallInfo) },
        )
    }

    fun didDismissPaywall(paywallInfo: PaywallInfo) {
        dispatch(
            callbackName = "didDismissPaywall",
            kotlinCallback = { it.didDismissPaywall(paywallInfo) },
            javaCallback = { it.didDismissPaywall(paywallInfo) },
        )
    }

    fun willPresentPaywall(paywallInfo: PaywallInfo) {
        dispatch(
            callbackName = "willPresentPaywall",
            kotlinCallback = { it.willPresentPaywall(paywallInfo) },
            javaCallback = { it.willPresentPaywall(paywallInfo) },
        )
    }

    fun didPresentPaywall(paywallInfo: PaywallInfo) {
        dispatch(
            callbackName = "didPresentPaywall",
            kotlinCallback = { it.didPresentPaywall(paywallInfo) },
            javaCallback = { it.didPresentPaywall(paywallInfo) },
        )
    }

    fun paywallWillOpenURL(url: URI) {
        dispatch(
            callbackName = "paywallWillOpenURL",
            kotlinCallback = { it.paywallWillOpenURL(url) },
            javaCallback = { it.paywallWillOpenURL(url) },
        )
    }

    fun paywallWillOpenDeepLink(url: Uri) {
        dispatch(
            callbackName = "paywallWillOpenDeepLink",
            kotlinCallback = { it.paywallWillOpenDeepLink(url) },
            javaCallback = { it.paywallWillOpenDeepLink(url) },
        )
    }

    fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
        dispatch(
            callbackName = "handleSuperwallEvent",
            kotlinCallback = { it.handleSuperwallEvent(eventInfo) },
            javaCallback = { it.handleSuperwallEvent(eventInfo) },
        )
    }

    fun subscriptionStatusDidChange(
        from: com.superwall.sdk.models.entitlements.SubscriptionStatus,
        to: com.superwall.sdk.models.entitlements.SubscriptionStatus,
    ) {
        dispatch(
            callbackName = "subscriptionStatusDidChange",
            kotlinCallback = { it.subscriptionStatusDidChange(from, to) },
            javaCallback = { it.subscriptionStatusDidChange(from, to) },
        )
    }

    fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?,
    ) {
        dispatch(
            callbackName = "handleLog",
            kotlinCallback = {
                it.handleLog(
                    level = level,
                    scope = scope,
                    message = message,
                    info = info,
                    error = error,
                )
            },
            javaCallback = {
                it.handleLog(
                    level = level,
                    scope = scope,
                    message = message,
                    info = info,
                    error = error,
                )
            },
        )
    }

    fun userAttributesDidChange(newAttributes: Map<String, Any>) {
        dispatch(
            callbackName = "userAttributesDidChange",
            kotlinCallback = { it.userAttributesDidChange(newAttributes) },
            javaCallback = { it.userAttributesDidChange(newAttributes) },
        )
    }

    fun customerInfoDidChange(
        from: CustomerInfo,
        to: CustomerInfo,
    ) {
        dispatch(
            callbackName = "customerInfoDidChange",
            kotlinCallback = { it.customerInfoDidChange(from, to) },
            javaCallback = { it.customerInfoDidChange(from, to) },
        )
    }
}
