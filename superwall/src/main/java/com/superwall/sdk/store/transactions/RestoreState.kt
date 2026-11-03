package com.superwall.sdk.store.transactions

import com.superwall.sdk.paywall.presentation.PaywallInfo

/**
 * Where the current restore stands. Owned by the restore actor; its reducers and actions
 * arrive with the restore flow.
 */
internal sealed class RestoreState {
    /** No restore has run yet, or the last one finished. */
    object Idle : RestoreState()

    /** A restore is running for the paywall described by [paywallInfo] (empty when started outside one). */
    data class Restoring(
        val paywallInfo: PaywallInfo,
    ) : RestoreState()
}
