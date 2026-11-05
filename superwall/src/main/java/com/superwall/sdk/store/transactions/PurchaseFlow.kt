package com.superwall.sdk.store.transactions

import com.android.billingclient.api.Purchase
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.store.ProductReplacement
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs purchases on a [SequentialActor] holding [TransactionState].
 *
 * Only the short steps go through the actor's queue: [TransactionState.Actions.Admit] decides
 * whether a purchase may start and reserves it, [TransactionState.Actions.Finish] releases it.
 * The purchase in between waits on the user in the store's sheet, so it runs inline in the
 * caller's coroutine instead of holding the queue: a second paywall tap still reaches
 * [TransactionState.Actions.Admit] while the first purchase runs, and is turned away.
 */
internal class PurchaseFlow(
    override val dependencies: TransactionDependencies,
    private val onObservedPurchase: (ObservedPurchase) -> Unit,
    override val scope: CoroutineScope = dependencies.ioScope,
    override val actor: StateActor<TransactionContext, TransactionState> =
        SequentialActor(TransactionState(), scope),
) : TransactionContext {
    override fun onObservedPurchaseStarted(purchase: ObservedPurchase) = onObservedPurchase(purchase)

    /**
     * Purchases [source]. [onAdmitted] runs once the purchase is allowed to start, before the
     * product is resolved; a paywall purchase uses it to show its loading state.
     */
    suspend fun purchase(
        source: PurchaseSource,
        shouldDismiss: Boolean,
        onAdmitted: () -> Unit = {},
    ): PurchaseResult {
        val admission = CompletableDeferred<PurchaseAdmission>()
        val admitted =
            try {
                immediate(TransactionState.Actions.Admit(source, onAdmitted, admission))
                admission.await()
            } catch (e: CancellationException) {
                // Admit is already on the queue and still runs after the caller stops waiting.
                // Release whatever it reserves, or the attempt blocks every later paywall purchase.
                releaseOnceAdmitted(admission)
                throw e
            }
        return when (admitted) {
            PurchaseAdmission.Rejected -> PurchaseResult.Failed(ALREADY_PURCHASING)
            PurchaseAdmission.ProductMissing -> PurchaseResult.Failed("Product not found")
            is PurchaseAdmission.Admitted ->
                try {
                    val result = CompletableDeferred<PurchaseResult>()
                    runInline(
                        TransactionState.Actions.Run(admitted.attemptId, admitted.product, source, shouldDismiss, result),
                    )
                    result.await()
                } finally {
                    // Queued without suspending, so a cancelled caller still releases the attempt.
                    effect(TransactionState.Actions.Finish(admitted.attemptId))
                }
        }
    }

    /** Queues [TransactionState.Actions.Finish] for the attempt [admission] reserves, if any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun releaseOnceAdmitted(admission: CompletableDeferred<PurchaseAdmission>) {
        admission.invokeOnCompletion { error ->
            if (error != null) return@invokeOnCompletion
            (admission.getCompleted() as? PurchaseAdmission.Admitted)?.let {
                effect(TransactionState.Actions.Finish(it.attemptId))
            }
        }
    }

    /** Tracks the start of a purchase the SDK didn't start, such as one seen in observer mode. */
    suspend fun prepareToPurchase(
        product: StoreProduct,
        source: PurchaseSource,
    ) = runInline(TransactionState.Actions.PrepareToPurchase(product, source))

    /** Acts on the end of a purchase the SDK didn't start, such as one seen in observer mode. */
    suspend fun settle(
        product: StoreProduct,
        source: PurchaseSource,
        outcome: PurchaseOutcome,
    ) = runInline(TransactionState.Actions.Settle(null, product, source, outcome, shouldDismiss = true))

    /** Removes [removed] from the persisted purchasing product ids. */
    fun clearPurchasingIds(removed: List<String>) = effect(TransactionState.Actions.ClearPurchasingIds(removed))

    internal companion object {
        const val ALREADY_PURCHASING = "A purchase is already in progress."
    }
}

/**
 * Runs [action] in the calling coroutine instead of on the actor's queue. Used for the parts of
 * a purchase that wait on the user or the store; their state changes still go through [update].
 */
internal suspend fun TransactionContext.runInline(action: TypedAction<TransactionContext>) = action.execute(this)

/**
 * Finds the subscription a purchase should replace when the paywall asked for a replacement.
 * Only an active subscription that shares an entitlement with [product] is replaced; without
 * one, the purchase is a normal purchase. Fails when Play can't say which subscriptions the
 * user has, since buying anyway could add a second one.
 */
internal suspend fun TransactionContext.resolveReplacement(
    product: StoreProduct,
    source: PurchaseSource,
): Result<ProductReplacement?> {
    val mode =
        (source as? PurchaseSource.Internal)?.replacementMode
            ?: return Result.success(null)
    // Only a subscription can replace a subscription
    if (product.subscriptionPeriod == null) {
        transactionLog(message = "Not replacing a subscription: ${product.fullIdentifier} isn't a subscription.")
        return Result.success(null)
    }
    val entitlementsById = dependencies.entitlementsById
    val entitlementIds = entitlementsById(product.fullIdentifier).map { it.id }.toSet()
    if (entitlementIds.isEmpty()) {
        transactionLog(message = "Not replacing a subscription: ${product.fullIdentifier} has no entitlements.")
        return Result.success(null)
    }

    val replacement =
        if (product.isCustomProduct) {
            // Custom store subscriptions aren't visible to Play, so go by the active
            // entitlements the PurchaseController reported.
            val shared =
                (dependencies.subscriptionStatus() as? SubscriptionStatus.Active)
                    ?.entitlements
                    ?.filter { it.id in entitlementIds }
                    .orEmpty()
            if (shared.isEmpty()) {
                null
            } else {
                ProductReplacement(
                    mode = mode,
                    oldProductId = shared.firstNotNullOfOrNull { it.latestProductId },
                    oldPurchaseToken = null,
                )
            }
        } else {
            val purchases =
                dependencies.storeManager.billing.querySubscriptionPurchases().getOrElse {
                    transactionLog(
                        logLevel = LogLevel.warn,
                        message = "Couldn't read the user's subscriptions to replace one with ${product.fullIdentifier}.",
                        error = it,
                    )
                    return Result.failure(it)
                }
            purchases
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .filter { purchase ->
                    purchase.products.any { productId ->
                        entitlementsById(productId).any { it.id in entitlementIds }
                    }
                }.maxByOrNull { it.purchaseTime }
                ?.let {
                    ProductReplacement(
                        mode = mode,
                        oldProductId = it.products.firstOrNull(),
                        oldPurchaseToken = it.purchaseToken,
                    )
                }
        }

    transactionLog(
        message =
            replacement?.let { "Replacing ${it.oldProductId} with ${product.fullIdentifier} (${mode.rawName})." }
                ?: "Not replacing a subscription: none shares an entitlement with ${product.fullIdentifier}.",
    )
    return Result.success(replacement)
}

/**
 * The latest store transaction, for tracking. With an external purchase controller the purchase
 * may not go through Google Play at all (e.g. Galaxy Store), so the billing client never emits a
 * result; the wait is bounded there and falls back to null.
 */
internal suspend fun TransactionDependencies.awaitLatestTransaction(): StoreTransaction? {
    val transactionVerifier = factory.makeTransactionVerifier()
    return if (factory.makeHasExternalPurchaseController()) {
        withTimeoutOrNull(TRANSACTION_LOOKUP_TIMEOUT_MS) {
            transactionVerifier.getLatestTransaction(factory)
        }
    } else {
        transactionVerifier.getLatestTransaction(factory)
    }
}

internal suspend fun TransactionContext.awaitLatestTransaction(): StoreTransaction? = dependencies.awaitLatestTransaction()

private const val TRANSACTION_LOOKUP_TIMEOUT_MS = 5_000L
