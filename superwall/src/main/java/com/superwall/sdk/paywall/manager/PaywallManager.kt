package com.superwall.sdk.paywall.manager

import com.superwall.sdk.dependencies.CacheFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.ViewControllerFactory
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class PaywallManager(
    private val factory: PaywallManager.Factory,
    private val paywallRequestManager: PaywallRequestManager
) {
    interface Factory: ViewControllerFactory, CacheFactory, DeviceHelperFactory {}

    var presentedViewController: PaywallViewController? = null
        get() = cache.activePaywallViewController

    private val queue = Mutex()
    private var _cache: PaywallViewControllerCache? = null

    private val cache: PaywallViewControllerCache
        get() {
            if (_cache == null) {
                _cache = createCache()
            }
            return _cache!!
        }

    private fun createCache(): PaywallViewControllerCache {
        val cache: PaywallViewControllerCache = factory.makeCache()
        _cache = cache
        return cache
    }

    fun removePaywallViewController(forKey: String) {
        cache.removePaywallViewController(forKey)
    }

    fun resetCache() {
        cache.removeAll()
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
            delegate = delegate
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
