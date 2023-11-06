package com.superwall.sdk.paywall.manager

import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

class PaywallViewControllerCache(private val deviceLocaleString: String) {

    private var _activePaywallVcKey: String? = null
    private val cache = ConcurrentHashMap<String, PaywallViewController>()
    private val singleThreadContext = newSingleThreadContext("com.superwall.paywallcache")

    fun getAllPaywallViewControllers(): List<PaywallViewController>  {
        return runBlocking(singleThreadContext) {
            cache.values.toList()
        }
    }

    var activePaywallVcKey: String?
        get() = runBlocking(singleThreadContext) { _activePaywallVcKey }
        set(value) {
            CoroutineScope(singleThreadContext).launch { _activePaywallVcKey = value }.apply { }
        }

    val activePaywallViewController: PaywallViewController?
        get() = runBlocking(singleThreadContext) { _activePaywallVcKey?.let { cache[it] } }

    fun save(paywallViewController: PaywallViewController, key: String) {
        CoroutineScope(singleThreadContext).launch { cache[key] = paywallViewController }
    }

    fun getPaywallViewController(key: String): PaywallViewController? {
        return runBlocking(singleThreadContext) { cache[key] }
    }

    fun removePaywallViewController(key: String) {
        CoroutineScope(singleThreadContext).launch { cache.remove(key) }
    }

    fun removeAll() {
        CoroutineScope(singleThreadContext).launch {
            cache.keys.forEach { key ->
                if (key != _activePaywallVcKey) {
                    cache.remove(key)
                }
            }
        }
    }
}
