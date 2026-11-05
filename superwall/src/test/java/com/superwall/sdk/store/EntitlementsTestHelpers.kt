package com.superwall.sdk.store

import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope

/** Builds [Entitlements] the way [com.superwall.sdk.dependencies.DependencyContainer] does. */
internal fun makeEntitlements(
    storage: Storage,
    scope: CoroutineScope,
): Entitlements =
    Entitlements(
        storage = storage,
        actor = StateActor(createInitialEntitlementsState(storage), scope),
        actorScope = scope,
    )
