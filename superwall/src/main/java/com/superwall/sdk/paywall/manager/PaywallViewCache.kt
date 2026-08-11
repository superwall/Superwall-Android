package com.superwall.sdk.paywall.manager

import android.content.Context
import androidx.annotation.ColorRes
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.models.paywall.PaywallIdentifier
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.view.LoadingView
import com.superwall.sdk.paywall.view.PaywallPurchaseLoadingView
import com.superwall.sdk.paywall.view.PaywallShimmerView
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.ShimmerView
import com.superwall.sdk.paywall.view.ViewStorage

class PaywallViewCache(
    private val appCtx: Context,
    private val store: ViewStorage,
    private val activityProvider: ActivityProvider,
    private val deviceHelper: DeviceHelper,
    @ColorRes private val loadingColor: Int? = null,
) {
    private val ctx: Context
        get() = activityProvider.getCurrentActivity() ?: appCtx

    @Volatile
    private var _activePaywallVcKey: String? = null
    private val loadingView: LoadingView = LoadingView(context = ctx, loadingColor = loadingColor)
    private val shimmerView: ShimmerView = ShimmerView(context = ctx)

    init {
        store.storeView(LoadingView.TAG, loadingView)
        store.storeView(ShimmerView.TAG, shimmerView)
    }

    fun getAllPaywallViews(): List<PaywallView> = store.all().filterIsInstance<PaywallView>().toList()

    var activePaywallVcKey: String?
        get() = _activePaywallVcKey
        set(value) {
            _activePaywallVcKey = value
        }

    val activePaywallView: PaywallView?
        get() = _activePaywallVcKey?.let { store.retrieveView(it) as PaywallView? }

    fun save(
        paywallView: PaywallView,
        identifier: PaywallIdentifier,
    ) {
        store.storeView(
            PaywallCacheLogic.key(
                identifier,
                locale = deviceHelper.locale,
            ),
            paywallView,
        )
    }

    fun acquireLoadingView(): PaywallPurchaseLoadingView {
        return store.retrieveView(LoadingView.TAG)?.let {
            it as PaywallPurchaseLoadingView
        } ?: run {
            val view = LoadingView(ctx, loadingColor = loadingColor)
            store.storeView(LoadingView.TAG, view)
            return view
        }
    }

    fun acquireShimmerView(): PaywallShimmerView {
        return store.retrieveView(ShimmerView.TAG)?.let {
            it as PaywallShimmerView
        } ?: run {
            val view = ShimmerView(ctx)
            store.storeView(ShimmerView.TAG, view)
            return view
        }
    }

    fun getPaywallView(key: String): PaywallView? =
        try {
            store.retrieveView(key) as PaywallView?
        } catch (e: Throwable) {
            null
        }

    fun removePaywallView(identifier: PaywallIdentifier) {
        store.removeView(
            PaywallCacheLogic.key(
                identifier,
                locale = deviceHelper.locale,
            ),
        )
    }

    fun removeAll() {
        store.views.keys.forEach { key ->
            if (key != _activePaywallVcKey) {
                store.removeView(key)
            }
        }
    }
}
