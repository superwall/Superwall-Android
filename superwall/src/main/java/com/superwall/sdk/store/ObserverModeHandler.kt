package com.superwall.sdk.store

import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.billing.google.toObservedReport
import com.superwall.sdk.billing.google.toStorePurchaseUpdates
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.SuperwallScope
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager

/**
 * Tracks purchases the app makes outside Superwall's paywalls, when
 * `SuperwallOptions.shouldObservePurchases` is on.
 */
internal class ObserverModeHandler(
    private val scope: SuperwallScope,
    private val shouldObservePurchases: () -> Boolean,
    private val transactionManager: () -> TransactionManager,
) {
    /** Hands [state] to the transaction manager, or logs an error when observing is off. */
    fun observe(state: PurchasingObserverState) {
        scope.launchWithTracking {
            if (!shouldObservePurchases()) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message =
                        "You are trying to observe purchases but the SuperwallOption shouldObservePurchases is " +
                            "false. Please set it to true to be able to observe purchases.",
                )
                return@launchWithTracking
            }
            val transactionManager = transactionManager()
            when (state) {
                is PurchasingObserverState.PurchaseWillBegin -> {
                    val product = StoreProduct(RawStoreProduct.from(state.product))
                    transactionManager.prepareToPurchase(
                        product,
                        source = TransactionManager.PurchaseSource.ObserverMode(product),
                    )
                }

                // The app's Play types become neutral ones here, at the public edge.
                is PurchasingObserverState.PurchaseResult -> {
                    val report = state.toObservedReport() ?: return@launchWithTracking
                    for (update in state.toStorePurchaseUpdates()) {
                        transactionManager.handle(update, report)
                    }
                }

                is PurchasingObserverState.PurchaseError -> {
                    val report = state.toObservedReport() ?: return@launchWithTracking
                    transactionManager.handle(StorePurchaseUpdate.Failed(state.error), report)
                }
            }
        }
    }
}
