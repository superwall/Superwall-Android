package com.superwall.sdk.paywall.manager

import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class PaywallViewControllerCache(private val deviceLocaleString: String) {

    private var _activePaywallVcKey: String? = null
    private val queue = Mutex()
    private val cache = ConcurrentHashMap<String, PaywallViewController>()
    private val scope = CoroutineScope(Dispatchers.Default)

    var activePaywallVcKey: String?
        get() = runBlocking { queue.withLock { _activePaywallVcKey } }
        set(value)  {
            scope.launch {
                queue.withLock {
                    _activePaywallVcKey = value
                }
            }
        }

    val activePaywallViewController: PaywallViewController?
        get() = runBlocking {
            queue.withLock {
                _activePaywallVcKey?.let { cache[it] }
            }
        }

    fun save(paywallViewController: PaywallViewController, key: String) {
        scope.launch { queue.withLock { cache[key] = paywallViewController } }
    }

    fun getPaywallViewController(key: String): PaywallViewController? {
        return runBlocking { queue.withLock { cache[key] } }
    }

    fun removePaywallViewController(key: String) {
        scope.launch { queue.withLock { cache.remove(key) } }
    }

    fun removeAll() {
        scope.launch {
            queue.withLock {
                cache.keys.forEach { key ->
                    if (key != _activePaywallVcKey) {
                        cache.remove(key)
                    }
                }
            }
        }
    }
}
