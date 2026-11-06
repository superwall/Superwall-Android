package com.superwallsupport.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Feeds a transport the app's foreground state (the process lifecycle) and the
 * network (the system's network callback): the two signals that decide when the
 * socket is held and when it reconnects at once.
 */
class SupportLifecycleMonitor(
    context: Context,
    private val transport: SupportTransport,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val available = HashSet<Network>()

    private val lifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = transport.setForeground(true)

            override fun onStop(owner: LifecycleOwner) = transport.setForeground(false)
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(available) { available.add(network) }
                // Also on a handover (cell up before Wi-Fi is lost): the transport probes its socket.
                transport.setNetworkAvailable(true)
            }

            override fun onLost(network: Network) {
                val none = synchronized(available) { available.remove(network); available.isEmpty() }
                // Another network remains (Wi-Fi lost, cell up): the socket may have been on the
                // lost one, so "still available" makes the transport probe it.
                transport.setNetworkAvailable(!none)
            }
        }

    /** Starts observing, seeding both signals with the current state. Call on the main thread. */
    fun start() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        // addObserver replays only up to the current state; a process started in the
        // background must not hold the socket.
        transport.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val active = connectivity?.activeNetwork
        val online =
            active != null &&
                connectivity.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        transport.setNetworkAvailable(online)
        lifecycle.addObserver(lifecycleObserver)
        // registerDefaultNetworkCallback needs API 24; minSdk is 23.
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        connectivity?.registerNetworkCallback(request, networkCallback)
    }

    fun stop() {
        synchronized(available) { available.clear() }
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        }
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }
}
