package com.superwall.sdk.store.transactions

import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Purchases started outside the SDK that it observes until Play reports their result.
 *
 * Order matters: a cancel or failure resolves the most recent one, so [observed] is a list,
 * oldest first. Owned by [ObserverModeTracker].
 */
internal data class ObserverModeState(
    val observed: List<ObservedPurchase> = emptyList(),
) {
    val latest: ObservedPurchase?
        get() = observed.lastOrNull()

    val isObserving: Boolean
        get() = observed.isNotEmpty()

    // -----------------------------------------------------------------------
    // Pure state mutations — (ObserverModeState) -> ObserverModeState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (ObserverModeState) -> ObserverModeState,
    ) : Reducer<ObserverModeState> {
        /** Starts observing [purchase]; a product already observed moves to the latest position. */
        data class Begin(
            val purchase: ObservedPurchase,
        ) : Updates({ state ->
                state.copy(observed = state.observed.filterNot { it.productId == purchase.productId } + purchase)
            })

        /** Stops observing the purchases of [productIds]. */
        data class Remove(
            val productIds: Collection<String>,
        ) : Updates({ state -> state.copy(observed = state.observed.filterNot { it.productId in productIds }) })
    }

    // -----------------------------------------------------------------------
    // Actions — async work via ObserverModeContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend ObserverModeContext.() -> Unit,
    ) : TypedAction<ObserverModeContext> {
        /**
         * Runs on the serial queue, so two results can't resolve the same purchase: completes
         * [claimed] with the latest observed purchase and stops observing it, or with null.
         */
        data class ClaimLatest(
            val claimed: CompletableDeferred<ObservedPurchase?>,
        ) : Actions({
                val latest = state.value.latest
                if (latest != null) update(Updates.Remove(listOf(latest.productId)))
                claimed.complete(latest)
            })

        /**
         * Runs on the serial queue: completes [claimed] with the observed purchase a store
         * [result] belongs to and stops observing it, or with null. A purchase is matched by its
         * product IDs; a cancel or failure carries no product, so it resolves the latest one.
         */
        data class ClaimForStoreResult(
            val result: StorePurchaseUpdate,
            val claimed: CompletableDeferred<ObservedPurchase?>,
        ) : Actions({
                val match =
                    when (result) {
                        is StorePurchaseUpdate.Purchased ->
                            state.value.observed.lastOrNull { it.matchesAny(result.purchase.productIds) }
                        StorePurchaseUpdate.Cancelled, is StorePurchaseUpdate.Failed -> state.value.latest
                        // The store's listener never reports these; the observe API passes them
                        // with their purchases instead.
                        StorePurchaseUpdate.Pending, StorePurchaseUpdate.Restored -> null
                    }
                if (match != null) update(Updates.Remove(listOf(match.productId)))
                claimed.complete(match)
            })

        /**
         * Follows the store's purchase results while a purchase is observed, when the options ask
         * the SDK to observe purchases. A result that arrives while the SDK runs its own purchase
         * belongs to that purchase, which tracks it, so it is skipped. Every other result claims
         * its observed purchase once and settles it.
         */
        object ObserveBillingResults : Actions({
            scope.launch {
                dependencies.store.latestPurchaseUpdate
                    .dropWhile { !state.value.isObserving }
                    .filterNotNull()
                    .collectLatest { result ->
                        if (isSdkPurchaseInFlight) return@collectLatest
                        val claimed = CompletableDeferred<ObservedPurchase?>()
                        immediate(ClaimForStoreResult(result, claimed))
                        val purchase = claimed.await() ?: return@collectLatest
                        runInline(Settle(result, reportFor(result, purchase), purchase))
                    }
            }
        })

        /** Acts on a store [result] reported through the observe API, for the latest observed purchase. */
        data class Handle(
            val result: StorePurchaseUpdate,
            val report: ObservedReport,
        ) : Actions(exec@{
                val claimed = CompletableDeferred<ObservedPurchase?>()
                immediate(ClaimLatest(claimed))
                val latest =
                    claimed.await() ?: run {
                        Logger.debug(
                            LogLevel.error,
                            LogScope.paywallTransactions,
                            "Transactions in progress is empty but a transaction was attempted to be handled." +
                                " This is likely a bug. Have you tracked the purchase start?",
                        )
                        return@exec
                    }
                runInline(Settle(result, report, latest))
            })

        /** Settles the already claimed [purchase] with a store [result]. */
        data class Settle(
            val result: StorePurchaseUpdate,
            val report: ObservedReport,
            val purchase: ObservedPurchase,
        ) : Actions({
                when (result) {
                    is StorePurchaseUpdate.Purchased -> {
                        dependencies.refreshReceipt()
                        forEachPurchasedProduct(report) { product, storePurchase ->
                            settlePurchase(product, PurchaseOutcome.Purchased(product.hasFreeTrial, storePurchase))
                        }
                    }

                    StorePurchaseUpdate.Cancelled -> settlePurchase(purchase.product, PurchaseOutcome.Cancelled)

                    is StorePurchaseUpdate.Failed -> {
                        val error = report as? ObservedReport.Error
                        settlePurchase(
                            error?.product ?: purchase.product,
                            PurchaseOutcome.Failed(failureMessage(error?.error ?: result.error)),
                        )
                    }

                    StorePurchaseUpdate.Pending ->
                        forEachPurchasedProduct(report) { product, _ ->
                            settlePurchase(product, PurchaseOutcome.Pending)
                        }

                    StorePurchaseUpdate.Restored ->
                        forEachPurchasedProduct(report) { product, _ ->
                            restoredByPurchase(product)
                        }
                }
            })
    }
}

/** One observed purchase: the product the app is buying. */
internal data class ObservedPurchase(
    val productId: String,
    val product: StoreProduct,
) {
    /**
     * True when the store's [productIds] include this purchase. [productId] is the SDK's full
     * identifier (`product:basePlan:offer`); the store reports the bare product ID.
     */
    fun matchesAny(productIds: Collection<String>): Boolean =
        productId in productIds ||
            productId.substringBefore(':') in productIds ||
            product.productIdentifier in productIds
}

/** What the app or the store reported about an observed purchase, besides the update itself. */
internal sealed interface ObservedReport {
    /** The store's result, with the purchases it reported, if any. */
    data class Result(
        val purchases: List<StorePurchase>?,
    ) : ObservedReport

    /** The purchase of [product] failed with [error]. */
    data class Error(
        val error: Throwable,
        val product: StoreProduct,
    ) : ObservedReport
}

/** Runs [action] in the calling coroutine; its state changes still go through [update]. */
internal suspend fun ObserverModeContext.runInline(action: TypedAction<ObserverModeContext>) = action.execute(this)

private suspend fun ObserverModeContext.settlePurchase(
    product: StoreProduct,
    outcome: PurchaseOutcome,
) = settleObservedPurchase(product, PurchaseSource.ObserverMode(product), outcome)

private suspend fun ObserverModeContext.restoredByPurchase(product: StoreProduct) =
    restoredByObservedPurchase(product, PurchaseSource.ObserverMode(product))

/** Calls [block] for each cached product of each purchase in a successful [report]. */
private suspend fun ObserverModeContext.forEachPurchasedProduct(
    report: ObservedReport,
    block: suspend (StoreProduct, StorePurchase) -> Unit,
) {
    val purchases = (report as? ObservedReport.Result)?.purchases ?: return
    for (purchase in purchases) {
        for (productId in purchase.productIds) {
            dependencies.storeManager.getProductFromCache(productId)?.let { block(it, purchase) }
        }
    }
}

/** The message for a failed observed purchase, read from Play's error code when it has one. */
internal fun failureMessage(error: Throwable): String =
    error.localizedMessage
        ?.let {
            try {
                PlayBillingErrors.fromCode(it)
            } catch (e: Throwable) {
                Logger.debug(
                    LogLevel.error,
                    LogScope.nativePurchaseController,
                    message = "Play store issue occured, code: $it",
                )
                null
            }
        }?.message ?: "Unknown error"

/** The report for a store [result] that settles the observed [purchase]. */
private fun reportFor(
    result: StorePurchaseUpdate,
    purchase: ObservedPurchase,
): ObservedReport =
    when (result) {
        is StorePurchaseUpdate.Purchased -> ObservedReport.Result(listOf(result.purchase))
        is StorePurchaseUpdate.Failed -> ObservedReport.Error(result.error, purchase.product)
        else -> ObservedReport.Result(emptyList())
    }
