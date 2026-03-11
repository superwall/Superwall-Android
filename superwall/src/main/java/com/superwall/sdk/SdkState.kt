package com.superwall.sdk

import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.identity.IdentityState
import com.superwall.sdk.misc.primitives.Actor
import com.superwall.sdk.misc.primitives.ScopedState

/**
 * Root state composing all domain states.
 *
 * A single [Actor]<[SdkState]> holds the truth for the entire SDK.
 * Domain actions never see this type — they operate on their own
 * [ScopedState] projection. Only cross-cutting actions work at this level.
 */
data class SdkState(
    val identity: IdentityState = IdentityState(),
    val config: SdkConfigState = SdkConfigState(),
) {
    val isReady: Boolean get() = identity.isReady && config.isRetrieved
}

/** Scoped projection for identity state. */
fun Actor<SdkState>.identityState(): ScopedState<SdkState, IdentityState> =
    scoped(
        get = { it.identity },
        set = { root, sub -> root.copy(identity = sub) },
    )

/** Scoped projection for config state. */
fun Actor<SdkState>.configState(): ScopedState<SdkState, SdkConfigState> =
    scoped(
        get = { it.config },
        set = { root, sub -> root.copy(config = sub) },
    )
