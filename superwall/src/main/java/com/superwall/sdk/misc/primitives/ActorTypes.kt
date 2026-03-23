package com.superwall.sdk.misc.primitives

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A [StateActor] that serializes all action execution via a [Mutex].
 *
 * Actions dispatched with [effect] or [immediate] will never run concurrently —
 * if an action suspends (e.g. waiting on a network call), the next action waits
 * until the first fully completes.
 *
 * Re-entrant: actions that call [immediate] on the same actor (sub-actions)
 * skip the mutex since the parent already holds it.
 */
class SequentialActor<Context, S>(initial: S) : StateActor<Context, S>(
    initial, CoroutineScope(Dispatchers.IO),
) {
    private val mutex = Mutex()

    private class OwnerElement(val actor: Any) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OwnerElement>
    }

    override suspend fun <Ctx> executeAction(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        if (coroutineContext[OwnerElement]?.actor === this) {
            super.executeAction(ctx, action)
        } else {
            mutex.withLock {
                withContext(OwnerElement(this@SequentialActor)) {
                    super.executeAction(ctx, action)
                }
            }
        }
    }
}
