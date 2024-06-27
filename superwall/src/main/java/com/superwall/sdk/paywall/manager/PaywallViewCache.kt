package com.superwall.sdk.paywall.manager

import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PaywallViewCache {
    private var _activePaywallViewKey: String? = null
    private val cache = ConcurrentHashMap<String, PaywallView>()
    private val singleThreadContext = newSingleThreadContext("com.superwall.paywallcache")

    @Deprecated("Will be removed in the upcoming versions, use getAllPaywallViews instead")
    fun getAllPaywallViewControllers(): List<PaywallView> = getAllPaywallViews()

    fun getAllPaywallViews(): List<PaywallView> =
        runBlocking(singleThreadContext) {
            cache.values.toList()
        }

    @Deprecated("Will be removed in the upcoming versions, use activePaywallViewKey instead")
    var activePaywallVcKey: String? = activePaywallViewKey

    var activePaywallViewKey: String?
        get() = runBlocking(singleThreadContext) { _activePaywallViewKey }
        set(value) {
            CoroutineScope(singleThreadContext).launch { _activePaywallViewKey = value }.apply { }
        }


    @Deprecated("Will be removed in the upcoming versions, use activePaywallView instead")
    val activePaywallViewController: PaywallView? = activePaywallView

    val activePaywallView: PaywallView?
        get() = runBlocking(singleThreadContext) { _activePaywallViewKey?.let { cache[it] } }

    fun save(
        paywallView: PaywallView,
        key: String,
    ) {
        CoroutineScope(singleThreadContext).launch { cache[key] = paywallView }
    }


    @Deprecated("Will be removed in the upcoming versions, use getPaywallView instead")
    fun getPaywallViewController(key: String): PaywallView? = getPaywallView(key)

    fun getPaywallView(key: String): PaywallView? = runBlocking(singleThreadContext) { cache[key] }

    @Deprecated("Will be removed in the upcoming versions, use removePaywallView instead")
    fun removePaywallViewController(key: String) = removePaywallView(key)

    fun removePaywallView(key: String) {
        CoroutineScope(singleThreadContext).launch { cache.remove(key) }
    }

    fun removeAll() {
        CoroutineScope(singleThreadContext).launch {
            cache.keys.forEach { key ->
                if (key != _activePaywallViewKey) {
                    cache.remove(key)
                }
            }
        }
    }
}
