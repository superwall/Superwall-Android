package com.superwall.sdk.paywall.view

import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Applies a public [PaywallViewState.Updates] through the paywall's [StateActor].
 *
 * [PaywallViewState.Updates] is public API, so it can't implement the internal [Reducer]
 * without changing the API dump. This wrapper is the bridge: every state change of a
 * [PaywallView.PaywallController] goes through the actor as one of these.
 */
internal data class PaywallReducer(
    val update: PaywallViewState.Updates,
) : Reducer<PaywallViewState> {
    override val reduce: (PaywallViewState) -> PaywallViewState = update.transform
}

/** Context for the paywall's actions: the controller's actor plus what the actions read. */
internal interface PaywallContext : BaseContext<PaywallViewState, PaywallContext> {
    /** The customer info the paywall shows, kept in [PaywallViewState.customerInfo]. */
    val customerInfo: () -> Flow<CustomerInfo>

    /** Applies a public [PaywallViewState.Updates] to the paywall's state. */
    fun update(update: PaywallViewState.Updates) = update(PaywallReducer(update))
}

internal class PaywallContextImpl(
    override val actor: StateActor<PaywallContext, PaywallViewState>,
    override val scope: CoroutineScope,
    override val storage: Storage,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    override val customerInfo: () -> Flow<CustomerInfo>,
) : PaywallContext

/** Async work on the paywall's state. */
internal sealed class PaywallActions(
    override val execute: suspend PaywallContext.() -> Unit,
) : TypedAction<PaywallContext> {
    /**
     * Mirrors the SDK's customer info into the paywall's state for as long as it runs.
     * It never completes on its own: the view runs it while presented and cancels it after.
     */
    data object ObserveCustomerInfo : PaywallActions({
        customerInfo().collect { update(PaywallViewState.Updates.SetCustomerInfo(it)) }
    })
}
