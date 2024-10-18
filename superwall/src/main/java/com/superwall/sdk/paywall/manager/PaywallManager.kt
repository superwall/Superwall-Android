package com.superwall.sdk.paywall.manager

import com.superwall.sdk.dependencies.CacheFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.SuperwallScopeFactory
import com.superwall.sdk.dependencies.ViewFactory
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.misc.mapAsync
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.paywall.PaywallIdentifier
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewDelegateAdapter

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

    @Deprecated("Will be removed in the upcoming versions, use curentView instead")
    var presentedViewController: PaywallView?
        get() = currentView
        set(value) {
            currentView = value
        }

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

    @Deprecated("Will be removed in the upcoming versions, use removePaywallView instead")
    fun removePaywallViewController(forKey: String) {
        removePaywallView(forKey)
    }

    fun removePaywallView(identifier: PaywallIdentifier) {
        cache.removePaywallView(identifier)
    }

    fun resetCache() {
        factory.mainScope().launchWithTracking {
            for (view in cache.getAllPaywallViews()) {
                view.webView.destroy()
                val inactivePaywalls =
                    cache.entries
                        .filter {
                            it.value is PaywallView &&
                                it.key != cache.activePaywallVcKey
                        }.values
                        .map { it as PaywallView }
                for (paywallView in inactivePaywalls) {
                    if (paywallView.paywall.identifier != cache.activePaywallVcKey) {
                        paywallView.webView.destroy()
                    }
                }
                cache.removeAll()
            }
        }
    }

    @Deprecated("Will be removed in the upcoming versions, use getPaywallView instead")
    suspend fun getPaywallViewController(
        request: PaywallRequest,
        isForPresentation: Boolean,
        isPreloading: Boolean,
        delegate: PaywallViewDelegateAdapter?,
    ): Result<PaywallView> = getPaywallView(request, isForPresentation, isPreloading, delegate).toResult()

    suspend fun getPaywallView(
        request: PaywallRequest,
        isForPresentation: Boolean,
        isPreloading: Boolean,
        delegate: PaywallViewDelegateAdapter?,
    ): Either<PaywallView, Throwable> =
        paywallRequestManager
            .getPaywall(request)
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
                            view.paywall.update(it)
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
