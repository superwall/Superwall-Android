package com.superwall.sdk.store

import com.superwall.sdk.billing.toInternalResult
import com.superwall.sdk.delegate.InternalPurchaseResult
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

                is PurchasingObserverState.PurchaseResult -> {
                    val result = (state.result to state.purchases).toInternalResult()
                    for (internalPurchaseResult in result) {
                        transactionManager.handle(internalPurchaseResult, state)
                    }
                }

                is PurchasingObserverState.PurchaseError -> {
                    transactionManager.handle(InternalPurchaseResult.Failed(state.error), state)
                }
            }
        }
    }
}
