package com.superwall.sdk.dependencies

import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.trackable.Trackable

/**
 * The one place SDK code reaches the configured [DependencyContainer] without it being passed in.
 *
 * Only entry points the SDK does not construct may read it: Activities and views the Android
 * framework instantiates, and static helpers such as the logger and error tracking. Everything
 * the container builds receives its dependencies through its constructor instead.
 *
 * [com.superwall.sdk.Superwall] attaches its container as soon as it is built and detaches it on
 * teardown, so [container] is `null` before `configure` and after `teardown`.
 */
internal object SuperwallGraph {
    @Volatile
    private var current: DependencyContainer? = null

    /** The configured container, or `null` when the SDK is not configured. */
    val container: DependencyContainer?
        get() = current

    /** The configured container. Throws when the SDK is not configured. */
    fun requireContainer(): DependencyContainer =
        current ?: throw IllegalStateException("Superwall has not been initialized or configured.")

    /**
     * Tracks [event] through the configured container. Only for entry points the container does not
     * build, and for the default `track` of public constructors kept for compatibility.
     */
    suspend fun track(event: Trackable): Result<TrackingResult> =
        container?.trackEvent(event)
            ?: Result.failure(IllegalStateException("Superwall has not been initialized or configured."))

    internal fun attach(container: DependencyContainer) {
        synchronized(this) { current = container }
    }

    /** Detaches [container] if it is still the attached one, so a stale teardown can't clear a newer graph. */
    internal fun detach(container: DependencyContainer?) {
        synchronized(this) {
            if (current === container) current = null
        }
    }
}
