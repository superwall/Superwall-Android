package com.superwall.sdk.config

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.superwall.sdk.misc.SuperwallScope
import com.superwall.sdk.misc.launchWithTracking

/**
 * Calls [onChange] whenever the app's Android [Configuration] changes. Locale, region,
 * language, timezone and the interface style (when it isn't overridden) all come from it.
 */
internal class AppConfigChangeObserver(
    private val context: Context,
    private val scope: SuperwallScope,
    private val onChange: suspend () -> Unit,
) {
    private var listener: ComponentCallbacks2? = null

    /** Starts listening. Calling it again while listening does nothing. */
    fun register() {
        if (listener != null) return
        // ComponentCallbacks2 fires on the main thread; bounce through the scope to call the
        // suspend [onChange].
        val callbacks =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    scope.launchWithTracking { onChange() }
                }

                override fun onLowMemory() = Unit

                override fun onTrimMemory(level: Int) = Unit
            }
        listener = callbacks
        context.applicationContext.registerComponentCallbacks(callbacks)
    }

    /** Stops listening. Does nothing when not listening. */
    fun unregister() {
        listener?.let {
            context.applicationContext.unregisterComponentCallbacks(it)
            listener = null
        }
    }
}
