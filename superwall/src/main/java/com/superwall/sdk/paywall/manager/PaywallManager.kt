package com.superwall.sdk.paywall.manager

import com.superwall.sdk.dependencies.ViewControllerCacheDevice
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate
import kotlinx.coroutines.sync.Mutex

class PaywallManager(
    private val factory: ViewControllerCacheDevice,
    private val paywallRequestManager: PaywallRequestManager
) {

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
        presentationRequest: PresentationRequest,
        isPreloading: Boolean,
        delegate: PaywallViewControllerDelegate?
    ): PaywallViewController {
        println("!!!PaywallManager.getPaywallViewController: Get")
        val paywall = paywallRequestManager.getPaywall(request)
        println("!!!PaywallManager.getPaywallViewController: paywall = $paywall")
        val deviceInfo = factory.makeDeviceInfo()
        val cacheKey =
            PaywallCacheLogic.key(identifier = paywall.identifier, locale = deviceInfo.locale)

        if (!request.isDebuggerLaunched) {
            val viewController = cache.getPaywallViewController(cacheKey)
            if (viewController != null) {
                if (!isPreloading) {
                    viewController.delegate = delegate
                    viewController.paywall.overrideProductsIfNeeded(paywall)
                }
                return viewController
            }
        }

        val paywallViewController = factory.makePaywallViewController(
            presentationRequest = presentationRequest,
            paywall = paywall,
            cache = cache,
            delegate = delegate
        )
        cache.save(paywallViewController, cacheKey)

        // TODO: Handle the preloading
        // Preloads the view.
//        val _ = paywallViewController.view

        return paywallViewController
    }
}
