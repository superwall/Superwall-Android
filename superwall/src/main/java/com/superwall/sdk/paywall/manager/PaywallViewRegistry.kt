package com.superwall.sdk.paywall.manager

import android.view.View
import com.superwall.sdk.paywall.view.PaywallPurchaseLoadingView
import com.superwall.sdk.paywall.view.PaywallShimmerView

/**
 * What Activities and debug UI need from the paywall view cache: hand a view
 * to an Activity by key, look it up again, and borrow the shared loading and
 * shimmer views.
 *
 * Callers depend on this instead of [PaywallViewCache] or
 * [com.superwall.sdk.paywall.view.ViewStorage] directly, so every write keeps
 * the cache and the storage in sync and the cache can change underneath.
 */
internal interface PaywallViewRegistry {
    fun storeView(
        key: String,
        view: View,
    )

    fun removeView(key: String)

    fun retrieveView(key: String): View?

    fun acquireLoadingView(): PaywallPurchaseLoadingView

    fun acquireShimmerView(): PaywallShimmerView
}

internal fun PaywallViewCache.asRegistry(): PaywallViewRegistry =
    object : PaywallViewRegistry {
        override fun storeView(
            key: String,
            view: View,
        ) = this@asRegistry.storeView(key, view)

        override fun removeView(key: String) = this@asRegistry.removeView(key)

        // Read ViewStorage: it is the copy that survives Activity recreation.
        override fun retrieveView(key: String): View? = viewStorage.retrieveView(key)

        override fun acquireLoadingView() = this@asRegistry.acquireLoadingView()

        override fun acquireShimmerView() = this@asRegistry.acquireShimmerView()
    }
