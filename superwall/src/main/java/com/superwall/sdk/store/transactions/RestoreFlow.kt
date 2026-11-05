package com.superwall.sdk.store.transactions

import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

/**
 * Runs restores on an actor holding [RestoreState].
 *
 * A restore waits on the purchase controller, which may show its own UI, so it runs inline in
 * the caller's coroutine as before; only its state changes go through the actor. Restores were
 * never deduplicated, and still aren't.
 */
internal class RestoreFlow(
    override val dependencies: TransactionDependencies,
    override val scope: CoroutineScope = dependencies.ioScope,
    override val actor: StateActor<RestoreContext, RestoreState> =
        SequentialActor(RestoreState.Idle, scope),
) : RestoreContext {
    suspend fun restore(
        paywallView: PaywallView?,
        presentsFailureAlert: Boolean,
    ): RestorationResult {
        val result = CompletableDeferred<RestorationResult>()
        runInline(RestoreState.Actions.Restore(paywallView, presentsFailureAlert, result))
        return result.await()
    }

    /** A purchase the SDK observed turned out to restore [product]. */
    suspend fun restored(
        product: StoreProduct?,
        source: PurchaseSource,
    ) = runInline(RestoreState.Actions.Restored(product, source))
}

/** Runs [action] in the calling coroutine; its state changes still go through [update]. */
internal suspend fun RestoreContext.runInline(action: TypedAction<RestoreContext>) = action.execute(this)
