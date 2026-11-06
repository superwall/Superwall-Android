package com.superwall.sdk.store.transactions

import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CoroutineScope

/**
 * Tracks purchases the app makes itself (observer mode) on an actor holding [ObserverModeState].
 *
 * Claiming the purchase a result belongs to runs on the actor's serial queue; settling it waits
 * on the store, so it runs in the caller's coroutine and hands off to [settle] and [restored].
 */
internal class ObserverModeTracker(
    override val dependencies: TransactionDependencies,
    private val settle: suspend (StoreProduct, PurchaseSource, PurchaseOutcome) -> Unit,
    private val restored: suspend (StoreProduct, PurchaseSource) -> Unit,
    private val sdkPurchaseInFlight: () -> Boolean = { false },
    override val scope: CoroutineScope = dependencies.ioScope,
    override val actor: StateActor<ObserverModeContext, ObserverModeState> =
        SequentialActor(ObserverModeState(), scope),
) : ObserverModeContext {
    override val isSdkPurchaseInFlight: Boolean
        get() = sdkPurchaseInFlight()

    override suspend fun settleObservedPurchase(
        product: StoreProduct,
        source: PurchaseSource,
        outcome: PurchaseOutcome,
    ) = settle(product, source, outcome)

    override suspend fun restoredByObservedPurchase(
        product: StoreProduct,
        source: PurchaseSource,
    ) = restored(product, source)

    /** Starts following Play's results by itself when the options ask the SDK to observe purchases. */
    fun start() {
        if (dependencies.observesPurchases) effect(ObserverModeState.Actions.ObserveBillingResults)
    }

    /** The app started buying [purchase]. Applied at once so a quick result finds it. */
    fun begin(purchase: ObservedPurchase) = update(ObserverModeState.Updates.Begin(purchase))

    /** Acts on a store [result] for the latest observed purchase. */
    suspend fun handle(
        result: StorePurchaseUpdate,
        report: ObservedReport,
    ) = runInline(ObserverModeState.Actions.Handle(result, report))
}
