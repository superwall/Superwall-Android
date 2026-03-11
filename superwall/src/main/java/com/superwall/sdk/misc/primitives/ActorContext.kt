package com.superwall.sdk.misc.primitives

import kotlinx.coroutines.CoroutineScope

/**
 * Pure actor context — the minimal contract for action execution.
 *
 * Provides a [StateActor] for state reads/updates, a [CoroutineScope],
 * and a type-safe [effect] for fire-and-forget sub-action dispatch.
 *
 * SDK-specific concerns (storage, persistence) live in [SdkContext].
 */
interface ActorContext<S, Self : ActorContext<S, Self>> {
    val actor: StateActor<S>
    val scope: CoroutineScope

    /**
     * Fire-and-forget dispatch of a sub-action on this context's actor.
     *
     * Type-safe: [Self] is the implementing context, matching the action's
     * receiver type. The cast is guaranteed correct by the F-bounded constraint.
     */
    @Suppress("UNCHECKED_CAST")
    fun effect(action: TypedAction<Self>) {
        actor.dispatch(this as Self, action)
    }
}
