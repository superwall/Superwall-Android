package com.superwall.sdk.identity

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Clears what the SDK keeps for the current user. The identity actor calls [completeReset]
 * on `reset()` and when `identify` switches users; identity itself is left to the actor.
 */
internal class SdkResetter(
    private val scope: CoroutineScope,
    private val clearStorage: () -> Unit,
    private val clearPaywallCache: () -> Unit,
    private val clearPresentationItems: () -> Unit,
    private val resetConfig: () -> Unit,
    private val clearAppUserRedemptions: () -> Unit,
    private val clearProductsCache: () -> Unit = {},
    private val track: suspend (Trackable) -> Unit,
) {
    /** Clears storage, cached store products, the paywall cache, presentation items, config and redemptions, then tracks `Reset`. */
    fun completeReset() {
        withErrorTracking {
            clearStorage()
            clearProductsCache()
            clearPaywallCache()
            clearPresentationItems()
            resetConfig()
            clearAppUserRedemptions()
            scope.launch {
                track(InternalSuperwallEvent.Reset)
            }
        }
    }
}
