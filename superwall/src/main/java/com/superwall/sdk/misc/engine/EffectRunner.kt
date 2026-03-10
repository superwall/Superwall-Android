package com.superwall.sdk.misc.engine

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.identity.IdentityEffect
import com.superwall.sdk.identity.IdentityEffectDeps
import com.superwall.sdk.misc.primitives.Effect
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer

/**
 * Creates the top-level effect runner that the [Engine] calls for every effect.
 *
 * Two layers:
 * 1. **Shared effects** — Persist, Delete, Track. Handled identically for every domain.
 *    (Dispatch and Deferred are handled by the Engine directly — they never reach here.)
 * 2. **Domain effects** — self-executing via [IdentityEffectDeps] scope.
 *
 * Error tracking is NOT done here — the Engine wraps every launch in `withErrorTracking`.
 */
internal fun createEffectRunner(
    storage: Storage,
    track: suspend (Trackable) -> Unit,
    configProvider: () -> Config?,
    webPaywallRedeemer: (() -> WebPaywallRedeemer)?,
    testModeManager: TestModeManager?,
    deviceHelper: DeviceHelper,
    delegate: (() -> SuperwallDelegateAdapter)?,
    completeReset: () -> Unit = {},
    fetchAssignments: (suspend () -> Unit)? = null,
    notifyUserChange: ((Map<String, Any>) -> Unit)? = null,
): suspend (Effect, (SdkEvent) -> Unit) -> Unit {
    val identityDeps =
        object : IdentityEffectDeps {
            override val configProvider = configProvider
            override val webPaywallRedeemer = webPaywallRedeemer
            override val testModeManager = testModeManager
            override val deviceHelper = deviceHelper
            override val delegate = delegate
            override val completeReset = completeReset
            override val fetchAssignments = fetchAssignments
            override val notifyUserChange = notifyUserChange
        }

    return { effect, dispatch ->
        when (effect) {
            is Effect.Persist -> writeAny(storage, effect.storable, effect.value)
            is Effect.Delete -> deleteAny(storage, effect.storable)
            is Effect.Track -> track(effect.event)
            is IdentityEffect -> effect.execute(identityDeps, dispatch)
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers for type-erased storage operations
// ---------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
private fun writeAny(
    storage: Storage,
    storable: Storable<*>,
    value: Any,
) {
    (storable as Storable<Any>).let { storage.write(it, value) }
}

@Suppress("UNCHECKED_CAST")
private fun deleteAny(
    storage: Storage,
    storable: Storable<*>,
) {
    (storable as Storable<Any>).let { storage.delete(it) }
}
