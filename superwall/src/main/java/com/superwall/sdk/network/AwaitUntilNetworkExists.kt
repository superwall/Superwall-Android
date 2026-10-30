package com.superwall.sdk.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suspends until the device has a network that claims internet access, or until [timeout] passes.
 *
 * Any transport counts (WiFi, cellular, ethernet, VPN, ...). We don't require
 * `NET_CAPABILITY_VALIDATED` because captive portals and some VPNs never validate.
 * The timeout guarantees callers (e.g. the config retry loop) always get to try again.
 */
internal suspend fun Context.awaitUntilNetworkExists(
    checkInterval: Long = 1000,
    timeout: Duration = 30.seconds,
) {
    val alreadyConnected = isNetworkAvailable(this)
    withTimeoutOrNull(timeout) {
        if (!alreadyConnected) {
            awaitNetwork(this@awaitUntilNetworkExists, checkInterval)
        }
        delay(checkInterval)
    }
}

private suspend fun awaitNetwork(
    context: Context,
    checkInterval: Long,
) {
    val connectivityManager = context.connectivityManager()
    if (connectivityManager == null || !hasNetworkPermission(context)) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            connectivityManager.awaitDefaultNetworkWithInternet()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fall through to polling
        }
    }

    // API 23, or the callback couldn't be registered (e.g. too many callbacks): poll.
    while (!isNetworkAvailable(context)) {
        delay(checkInterval)
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
private suspend fun ConnectivityManager.awaitDefaultNetworkWithInternet() =
    suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                private fun complete() {
                    if (done.compareAndSet(false, true)) {
                        runCatching { unregisterNetworkCallback(this) }
                        continuation.resume(Unit)
                    }
                }

                override fun onAvailable(network: Network) {
                    val caps = getNetworkCapabilities(network) ?: return
                    if (caps.hasInternet()) complete()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.hasInternet()) complete()
                }
            }
        // Throws if registration fails; the caller falls back to polling.
        registerDefaultNetworkCallback(callback)
        continuation.invokeOnCancellation {
            if (done.compareAndSet(false, true)) {
                runCatching { unregisterNetworkCallback(callback) }
            }
        }
    }

private fun Context.connectivityManager(): ConnectivityManager? = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

private fun hasNetworkPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_NETWORK_STATE,
    ) == PackageManager.PERMISSION_GRANTED

internal fun NetworkCapabilities.hasInternet(): Boolean = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

internal fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.connectivityManager() ?: return true
    if (!hasNetworkPermission(context)) return true

    val network = connectivityManager.activeNetwork ?: return false
    val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
    return caps.hasInternet()
}
