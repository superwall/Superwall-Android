package com.superwall.sdk.paywall.manager

import android.content.Context
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.paywall.vc.LoadingView
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.ShimmerView
import com.superwall.sdk.paywall.vc.ViewStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PaywallViewCache(
    private val appCtx: Context,
    private val store: ViewStorage,
    private val activityProvider: ActivityProvider,
) {
    private val ctx: Context = activityProvider.getCurrentActivity() ?: appCtx
    private var _activePaywallVcKey: String? = null
    private val loadingView: LoadingView = LoadingView(context = ctx)
    private val shimmerView: ShimmerView = ShimmerView(context = ctx)

    init {
        store.storeView(LoadingView.TAG, loadingView)
        store.storeView(ShimmerView.TAG, shimmerView)
    }

    private val singleThreadContext = Dispatchers.IO

    val entries =
        store.views.entries
            .map { it.key to it.value }
            .toMap()

    @Deprecated("Will be removed in the upcoming versions in favor of `getPaywallViews`")
    fun getAllPaywallViewControllers(): List<PaywallView> = getAllPaywallViews()

    fun getAllPaywallViews(): List<PaywallView> =
        runBlocking(singleThreadContext) {
            store.all().filterIsInstance<PaywallView>().toList()
        }

    var activePaywallVcKey: String?
        get() = runBlocking(singleThreadContext) { _activePaywallVcKey }
        set(value) {
            CoroutineScope(singleThreadContext).launch { _activePaywallVcKey = value }.apply { }
        }

    val activePaywallView: PaywallView?
        get() = runBlocking(singleThreadContext) { _activePaywallVcKey?.let { store.retrieveView(it) as PaywallView? } }

    fun save(
        paywallView: PaywallView,
        key: String,
    ) {
        CoroutineScope(singleThreadContext).launch { store.storeView(key, paywallView) }
    }

    fun acquireLoadingView(): LoadingView {
        return store.retrieveView(LoadingView.TAG)?.let {
            it as LoadingView
        } ?: run {
            val view = LoadingView(ctx)
            store.storeView(LoadingView.TAG, view)
            return view
        }
    }

    fun acquireShimmerView(): ShimmerView {
        return store.retrieveView(ShimmerView.TAG)?.let {
            it as ShimmerView
        } ?: run {
            val view = ShimmerView(ctx)
            store.storeView(ShimmerView.TAG, view)
            return view
        }
    }

    fun getPaywallView(key: String): PaywallView? = runBlocking(singleThreadContext) { store.retrieveView(key) as PaywallView? }

    fun removePaywallView(key: String) {
        CoroutineScope(singleThreadContext).launch { store.removeView(key) }
    }

    fun removeAll() {
        CoroutineScope(singleThreadContext).launch {
            store.keys().forEach { key ->
                if (key != _activePaywallVcKey) {
                    store.removeView(key)
                }
            }
        }
    }
}
