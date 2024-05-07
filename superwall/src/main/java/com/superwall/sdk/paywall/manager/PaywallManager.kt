package com.superwall.sdk.paywall.manager

import com.superwall.sdk.dependencies.CacheFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.PaywallArchivalManagerFactory
import com.superwall.sdk.dependencies.ViewControllerFactory
import com.superwall.sdk.paywall.archival.PaywallArchivalManager
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaywallManager(
    private val factory: Factory,
    private val paywallRequestManager: PaywallRequestManager
) {
    interface Factory : ViewControllerFactory, CacheFactory, DeviceHelperFactory,
        PaywallArchivalManagerFactory

    var presentedViewController: PaywallViewController? = null
        get() = cache.activePaywallViewController

    private var _cache: PaywallViewControllerCache? = null

    private val cache: PaywallViewControllerCache
        get() {
            if (_cache == null) {
                _cache = createCache()
            }
            return _cache!!
        }

    private val paywallArchivalManager: PaywallArchivalManager =
        factory.makePaywallArchivalManager()

    private fun createCache(): PaywallViewControllerCache {
        val cache: PaywallViewControllerCache = factory.makeCache()
        _cache = cache
        return cache
    }

    fun removePaywallViewController(forKey: String) {
        cache.removePaywallViewController(forKey)
    }

    fun resetCache() {
        CoroutineScope(Dispatchers.Main).launch {
            for (paywallViewController in cache.getAllPaywallViewControllers()) {
                paywallViewController.webView.destroy()
            }

            cache.removeAll()
        }
    }

    /// First, this gets the paywall response for a specified paywall identifier or trigger event.
    /// It then checks with the archival manager to tell us if we should still eagerly create the
    /// view controller or not.
    ///
    /// - Parameters:
    ///   - request: The request to get the paywall.
    suspend fun preloadViaPaywallArchivalAndShouldSkipViewControllerCache(
        request: PaywallRequest
    ): Boolean {
        val paywall = paywallRequestManager.getPaywall(request = request)
        return paywallArchivalManager.preloadArchiveAndShouldSkipViewControllerCache(paywall = paywall)
    }

    suspend fun getPaywallViewController(
        request: PaywallRequest,
        isForPresentation: Boolean,
        isPreloading: Boolean,
        delegate: PaywallViewControllerDelegateAdapter?
    ): PaywallViewController = withContext(Dispatchers.Main) {
        val paywall = paywallRequestManager.getPaywall(request)

        val deviceInfo = factory.makeDeviceInfo()
        val cacheKey = PaywallCacheLogic.key(
            identifier = paywall.identifier,
            locale = deviceInfo.locale
        )

        if (!request.isDebuggerLaunched) {
            cache.getPaywallViewController(cacheKey)?.let { viewController ->
                if (!isPreloading) {
                    viewController.delegate = delegate
                    viewController.paywall.update(paywall)
                }
                return@withContext viewController
            }
        }

        val paywallViewController = factory.makePaywallViewController(
            paywall = paywall,
            cache = cache,
            delegate = delegate,
            paywallArchivalManager = paywallArchivalManager
        )
        cache.save(paywallViewController, cacheKey)

        if (isForPresentation) {
            // Only preload if it's actually gonna present the view.
            // Not if we're just checking its result
            // TODO: Handle the preloading
            if (paywallViewController.loadingState is PaywallLoadingState.Unknown) {
                paywallViewController.loadWebView()
            }
//            paywallViewController.loadViewIfNeeded()
        }

        return@withContext paywallViewController
    }
}
