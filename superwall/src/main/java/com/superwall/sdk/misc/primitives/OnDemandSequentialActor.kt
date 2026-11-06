package com.superwall.sdk.misc.primitives

import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A [StateActor] with the same guarantees as [SequentialActor] (actions run one at a time, in
 * dispatch order, and an action that calls back into this actor runs inline), but without a
 * long-lived consumer loop.
 *
 * A drain coroutine is launched in [scope] only while work is queued and ends once the queue is
 * empty. An idle actor holds nothing in [scope], so short-lived owners (such as one actor per
 * paywall view on a lifetime scope) can be dropped without closing them.
 */
internal class OnDemandSequentialActor<Context, S>(
    initial: S,
    scope: CoroutineScope,
) : StateActor<Context, S>(initial, scope) {
    private val queue = ConcurrentLinkedQueue<suspend () -> Unit>()
    private val draining = AtomicBoolean(false)

    private class OwnerElement(
        val actor: Any,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OwnerElement>
    }

    override suspend fun <Ctx> executeAction(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        if (coroutineContext[OwnerElement]?.actor === this) {
            // Re-entrant: already inside the drain — run inline.
            super.executeAction(ctx, action)
        } else {
            val done = CompletableDeferred<Unit>()
            schedule {
                try {
                    super.executeAction(ctx, action)
                    done.complete(Unit)
                } catch (e: Throwable) {
                    done.completeExceptionally(e)
                }
            }
            done.await()
        }
    }

    /** Fire-and-forget: queue the action in FIFO order and return immediately. */
    override fun <Ctx> effect(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        runInterceptorChain(action) {
            schedule {
                runAsyncInterceptorChain(action) { action.execute.invoke(ctx) }
            }
        }
    }

    private fun schedule(work: suspend () -> Unit) {
        queue.add(work)
        startDrainIfIdle()
    }

    private fun startDrainIfIdle() {
        if (draining.compareAndSet(false, true)) {
            scope.launch(OwnerElement(this@OnDemandSequentialActor)) { drain() }
        }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val work = queue.poll() ?: break
                // A throwing action must neither stop the actions after it nor escape to the scope.
                withErrorTracking { work() }
            }
        } finally {
            draining.set(false)
        }
        // Work queued between the last poll and releasing the flag would otherwise wait for the
        // next dispatch: start a new drain for it.
        if (queue.isNotEmpty()) startDrainIfIdle()
    }
}
