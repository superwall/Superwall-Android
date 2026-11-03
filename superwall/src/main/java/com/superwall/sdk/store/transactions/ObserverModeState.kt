package com.superwall.sdk.store.transactions

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asSharedFlow
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
         * Follows Play's purchase results while a purchase is observed, when the options ask the
         * SDK to observe purchases. Each result first updates what is observed, as the billing
         * listener always did, then is handled as [Handle].
         */
        object ObserveBillingResults : Actions({
            scope.launch {
                dependencies.storeManager.billing.purchaseResults
                    .asSharedFlow()
                    .dropWhile { !state.value.isObserving }
                    .filterNotNull()
                    .collectLatest { result ->
                        val observerState = observerStateFor(result) ?: return@collectLatest
                        runInline(Handle(result, observerState))
                    }
            }
        })

        /** Acts on a Play [result] for the latest observed purchase. */
        data class Handle(
            val result: InternalPurchaseResult,
            val observerState: PurchasingObserverState,
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

                when (result) {
                    is InternalPurchaseResult.Purchased -> {
                        dependencies.refreshReceipt()
                        forEachPurchasedProduct(observerState) { product, purchase ->
                            settlePurchase(product, PurchaseOutcome.Purchased(product.hasFreeTrial, purchase))
                        }
                    }

                    is InternalPurchaseResult.Cancelled -> {
                        val product = StoreProduct(RawStoreProduct.from(latest.details))
                        settlePurchase(product, PurchaseOutcome.Cancelled)
                    }

                    is InternalPurchaseResult.Failed -> {
                        val error = observerState as PurchasingObserverState.PurchaseError
                        val product = StoreProduct(RawStoreProduct.from(error.product))
                        settlePurchase(product, PurchaseOutcome.Failed(failureMessage(error.error)))
                    }

                    InternalPurchaseResult.Pending ->
                        forEachPurchasedProduct(observerState) { product, _ ->
                            settlePurchase(product, PurchaseOutcome.Pending)
                        }

                    InternalPurchaseResult.Restored ->
                        forEachPurchasedProduct(observerState) { product, _ ->
                            restoredByPurchase(product)
                        }
                }
            })
    }
}

/** One observed purchase: the product the app is buying and its Play details. */
internal data class ObservedPurchase(
    val productId: String,
    val details: ProductDetails,
)

/** Runs [action] in the calling coroutine; its state changes still go through [update]. */
internal suspend fun ObserverModeContext.runInline(action: TypedAction<ObserverModeContext>) = action.execute(this)

private suspend fun ObserverModeContext.settlePurchase(
    product: StoreProduct,
    outcome: PurchaseOutcome,
) = settleObservedPurchase(product, PurchaseSource.ObserverMode(product), outcome)

private suspend fun ObserverModeContext.restoredByPurchase(product: StoreProduct) =
    restoredByObservedPurchase(product, PurchaseSource.ObserverMode(product))

/** Calls [block] for each cached product of each purchase in a successful [observerState]. */
private suspend fun ObserverModeContext.forEachPurchasedProduct(
    observerState: PurchasingObserverState,
    block: suspend (StoreProduct, Purchase) -> Unit,
) {
    val purchases = (observerState as PurchasingObserverState.PurchaseResult).purchases ?: return
    for (purchase in purchases) {
        for (productId in purchase.products) {
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

/**
 * Turns a billing [result] into the observer state for [ObserverModeState.Actions.Handle], and
 * first stops observing what the result settles, as the billing listener always did. Returns
 * null when nothing is observed for a cancel or failure.
 */
private suspend fun ObserverModeContext.observerStateFor(result: InternalPurchaseResult): PurchasingObserverState? =
    when (result) {
        is InternalPurchaseResult.Purchased -> {
            update(ObserverModeState.Updates.Remove(result.purchase.products))
            PurchasingObserverState.PurchaseResult(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                listOf(result.purchase),
            )
        }

        is InternalPurchaseResult.Cancelled -> {
            val claimed = CompletableDeferred<ObservedPurchase?>()
            immediate(ObserverModeState.Actions.ClaimLatest(claimed))
            claimed.await()?.let {
                PurchasingObserverState.PurchaseResult(
                    BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED).build(),
                    emptyList(),
                )
            }
        }

        else -> {
            val claimed = CompletableDeferred<ObservedPurchase?>()
            immediate(ObserverModeState.Actions.ClaimLatest(claimed))
            claimed.await()?.let {
                PurchasingObserverState.PurchaseError(
                    error = (result as? InternalPurchaseResult.Failed)?.error ?: Throwable("Unknown error"),
                    product = it.details,
                )
            }
        }
    }
