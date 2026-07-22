package com.superwall.sdk.paywall.manager

import com.superwall.sdk.dependencies.CacheFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.SuperwallScopeFactory
import com.superwall.sdk.dependencies.ViewFactory
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.misc.mapAsync
import com.superwall.sdk.models.paywall.PaywallIdentifier
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.delegate.PaywallViewDelegateAdapter

class PaywallManager(
    private val factory: PaywallManager.Factory,
    private val paywallRequestManager: PaywallRequestManager,
) {
    interface Factory :
        ViewFactory,
        CacheFactory,
        DeviceHelperFactory,
        SuperwallScopeFactory

    var currentView: PaywallView? = null
        get() = cache.activePaywallView

    private var _cache: PaywallViewCache? = null

    private val cache: PaywallViewCache
        get() {
            if (_cache == null) {
                _cache = createCache()
            }
            return _cache!!
        }

    private fun createCache(): PaywallViewCache {
        val cache: PaywallViewCache = factory.makeCache()
        _cache = cache
        return cache
    }

    fun removePaywallView(identifier: PaywallIdentifier) {
        cache.removePaywallView(identifier)
    }

    fun resetCache() {
        factory.mainScope().launchWithTracking {
            val activeKey = cache.activePaywallVcKey
            cache
                .getAllPaywallViews()
                .filter { it.state.cacheKey != activeKey }
                .forEach { it.destroyWebview() }
            cache.removeAll()
        }
    }

    suspend fun getPaywallView(
        request: PaywallRequest,
        isForPresentation: Boolean,
        isPreloading: Boolean,
        delegate: PaywallViewDelegateAdapter?,
    ): Either<PaywallView, Throwable> =
        paywallRequestManager
            .getPaywall(request, isPreloading)
            .mapAsync {
                val deviceInfo = factory.makeDeviceInfo()
                val cacheKey =
                    PaywallCacheLogic.key(
                        identifier = it.identifier,
                        locale = deviceInfo.locale,
                    )

                if (!request.isDebuggerLaunched) {
                    cache.getPaywallView(cacheKey)?.let { view ->
                        if (!isPreloading) {
                            view.callback = delegate
                            view.updateState(PaywallViewState.Updates.MergePaywall(it))
                            // A cached view is being handed back for a new presentation. Clear any
                            // per-presentation transient state (a stale LoadingPurchase/ManualLoading
                            // spinner that swallows taps, and a stale presentationDidFinishPrepare)
                            // leaked by a previous presentation that stopped without a finishing
                            // teardown. This covers both the register()/full-screen path and the
                            // embedded getPaywallView/getPaywall path, which both funnel through here.
                            // Guarded by !isPreloading so preloading never resets a live view, and by
                            // isForPresentation because getPresentationResult() (a pure query API)
                            // also fetches through here — a result check while this paywall is
                            // on screen mid-purchase must not wipe its live spinner or prepare flags.
                            if (isForPresentation) {
                                view.resetTransientPresentationState()
                            }
                        }
                        return@mapAsync view
                    }
                }

                val paywallView =
                    factory.makePaywallView(
                        paywall = it,
                        cache = cache,
                        delegate = delegate,
                    )
                cache.save(paywallView, it.identifier)
                if (isForPresentation) {
                    // Only preload if it's actually gonna present the view.
                    // Not if we're just checking its result
                    // TODO: Handle the preloading
                    if (paywallView.loadingState is PaywallLoadingState.Unknown) {
                        paywallView.loadWebView()
                    }
//            paywallViewController.loadViewIfNeeded()
                }
                paywallView
            }

    internal fun resetPaywallRequestCache() {
        paywallRequestManager.resetCache()
    }
}
