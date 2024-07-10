package com.superwall.sdk.paywall.manager

import com.superwall.sdk.dependencies.CacheFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.ViewFactory
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewDelegateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaywallManager(
    private val factory: PaywallManager.Factory,
    private val paywallRequestManager: PaywallRequestManager,
) {
    interface Factory :
        ViewFactory,
        CacheFactory,
        DeviceHelperFactory

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

    fun removePaywallView(forKey: String) {
        cache.removePaywallView(forKey)
    }

    fun resetCache() {
        CoroutineScope(Dispatchers.Main).launch {
            for (view in cache.getAllPaywallViews()) {
                view.webView.destroy()
            }

            cache.removeAll()
        }
    }

    @Deprecated("Will be removed in the upcoming versions, use getPaywallView instead")
    suspend fun getPaywallViewController(
        request: PaywallRequest,
        isForPresentation: Boolean,
        isPreloading: Boolean,
        delegate: PaywallViewDelegateAdapter?,
    ): PaywallView = getPaywallView(request, isForPresentation, isPreloading, delegate)

    suspend fun getPaywallView(
        request: PaywallRequest,
        isForPresentation: Boolean,
        isPreloading: Boolean,
        delegate: PaywallViewDelegateAdapter?,
    ): PaywallView =
        withContext(Dispatchers.Main) {
            val paywall = paywallRequestManager.getPaywall(request)

            val deviceInfo = factory.makeDeviceInfo()
            val cacheKey =
                PaywallCacheLogic.key(
                    identifier = paywall.identifier,
                    locale = deviceInfo.locale,
                )

            if (!request.isDebuggerLaunched) {
                cache.getPaywallView(cacheKey)?.let { view ->
                    if (!isPreloading) {
                        view.callback = delegate
                        view.paywall.update(paywall)
                    }
                    return@withContext view
                }
            }

            val paywallView =
                factory.makePaywallView(
                    paywall = paywall,
                    cache = cache,
                    delegate = delegate,
                )
            cache.save(paywallView, cacheKey)

            if (isForPresentation) {
                // Only preload if it's actually gonna present the view.
                // Not if we're just checking its result
                // TODO: Handle the preloading
                if (paywallView.loadingState is PaywallLoadingState.Unknown) {
                    paywallView.loadWebView()
                }
//            paywallViewController.loadViewIfNeeded()
            }

            return@withContext paywallView
        }
}
