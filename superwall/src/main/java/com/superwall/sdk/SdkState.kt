package com.superwall.sdk

import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.identity.IdentityState
import com.superwall.sdk.misc.primitives.StateStore
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.store.EntitlementsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Read-only facade over all domain states.
 *
 * Each property delegates to the live [StateStore] of its slice —
 * no monolithic root state, no copying.
 */
class SdkState(
    private val identityStore: () -> StateStore<IdentityState>,
    private val configStore: () -> StateStore<SdkConfigState>,
    private val entitlementsStore: () -> StateStore<EntitlementsState>,
) {
    val identity: IdentityState get() = identityStore().state.value
    val config: SdkConfigState get() = configStore().state.value
    val entitlements: EntitlementsState get() = entitlementsStore().state.value
    val isReady: Boolean get() = identity.isReady && config.isRetrieved

    /** Suspend until config has been retrieved, then return it. */
    suspend fun awaitConfig(): Config? =
        configStore()
            .state
            .map { (it.phase as? SdkConfigState.Phase.Retrieved)?.config }
            .first { it != null }
}
