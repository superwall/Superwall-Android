package com.superwall.sdk.store.transactions

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Transaction.TransactionSource
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource

/**
 * Pure builders for the analytics events a purchase or restore emits.
 *
 * Nothing here tracks, logs or reads SDK state: every input is a parameter, so the
 * transaction actions decide *when* to send an event and these builders decide *what* it holds.
 */
internal object TransactionAnalytics {
    /** The demand-score enrichment every transaction event carries. */
    data class Demand(
        val score: Int?,
        val tier: String?,
    )

    /** Where a purchase came from, as the analytics backend names it (observer counts as external). */
    fun sourceOf(purchaseSource: PurchaseSource): TransactionSource =
        when (purchaseSource) {
            is PurchaseSource.Internal -> TransactionSource.INTERNAL
            is PurchaseSource.ExternalPurchase,
            is PurchaseSource.ObserverMode,
            -> TransactionSource.EXTERNAL
        }

    fun paywallInfoOf(purchaseSource: PurchaseSource): PaywallInfo =
        when (purchaseSource) {
            is PurchaseSource.Internal -> purchaseSource.paywallInfo
            is PurchaseSource.ExternalPurchase,
            is PurchaseSource.ObserverMode,
            -> PaywallInfo.empty()
        }

    private fun isObserverMode(purchaseSource: PurchaseSource) = purchaseSource is PurchaseSource.ObserverMode

    fun start(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        demand: Demand,
    ) = InternalSuperwallEvent.Transaction(
        state = InternalSuperwallEvent.Transaction.State.Start(product),
        paywallInfo = paywallInfoOf(purchaseSource),
        product = product,
        model = null,
        source = sourceOf(purchaseSource),
        isObserved = isObserverMode(purchaseSource),
        demandScore = demand.score,
        demandTier = demand.tier,
    )

    fun abandon(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        demand: Demand,
    ) = InternalSuperwallEvent.Transaction(
        state = InternalSuperwallEvent.Transaction.State.Abandon(product),
        paywallInfo = paywallInfoOf(purchaseSource),
        product = product,
        model = null,
        source = sourceOf(purchaseSource),
        isObserved = isObserverMode(purchaseSource),
        demandScore = demand.score,
        demandTier = demand.tier,
    )

    /**
     * A purchase waiting on approval (for example parental approval). A paywall purchase reports
     * the SDK's observe-purchases option; others report whether they came from observer mode.
     */
    fun pending(
        purchaseSource: PurchaseSource,
        observesPurchases: Boolean,
        demand: Demand,
    ) = InternalSuperwallEvent.Transaction(
        state = InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Pending(PENDING_MESSAGE)),
        paywallInfo = paywallInfoOf(purchaseSource),
        product = null,
        model = null,
        source = sourceOf(purchaseSource),
        isObserved = isObservedForFailure(purchaseSource, observesPurchases),
        demandScore = demand.score,
        demandTier = demand.tier,
    )

    fun failure(
        errorMessage: String,
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        observesPurchases: Boolean,
        demand: Demand,
    ) = InternalSuperwallEvent.Transaction(
        state = InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Failure(errorMessage, product)),
        paywallInfo = paywallInfoOf(purchaseSource),
        product = product,
        model = null,
        source = sourceOf(purchaseSource),
        isObserved = isObservedForFailure(purchaseSource, observesPurchases),
        demandScore = demand.score,
        demandTier = demand.tier,
    )

    private fun isObservedForFailure(
        purchaseSource: PurchaseSource,
        observesPurchases: Boolean,
    ) = if (purchaseSource is PurchaseSource.Internal) observesPurchases else isObserverMode(purchaseSource)

    /** A paywall purchase is never reported as observed; others report observer mode. */
    fun complete(
        product: StoreProduct,
        transaction: StoreTransaction?,
        purchaseSource: PurchaseSource,
        demand: Demand,
        userAttributes: Map<String, Any>?,
        storefrontCountryCode: String?,
    ) = InternalSuperwallEvent.Transaction(
        state = InternalSuperwallEvent.Transaction.State.Complete(product, transaction),
        paywallInfo = paywallInfoOf(purchaseSource),
        product = product,
        model = transaction,
        source = sourceOf(purchaseSource),
        isObserved = isObserverMode(purchaseSource),
        demandScore = demand.score,
        demandTier = demand.tier,
        userAttributes = userAttributes,
        storefrontCountryCode = storefrontCountryCode,
    )

    /** The event that says what kind of purchase completed: one-time, trial start or subscription start. */
    fun purchaseKind(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        didStartFreeTrial: Boolean,
    ): TrackableSuperwallEvent {
        val paywallInfo = paywallInfoOf(purchaseSource)
        return when {
            product.subscriptionPeriod == null -> InternalSuperwallEvent.NonRecurringProductPurchase(paywallInfo, product)
            didStartFreeTrial -> InternalSuperwallEvent.FreeTrialStart(paywallInfo, product)
            else -> InternalSuperwallEvent.SubscriptionStart(paywallInfo, product)
        }
    }

    /** A purchase that turned out to restore an existing one. Here observer mode keeps its own source. */
    fun restored(
        product: StoreProduct?,
        restoreType: RestoreType,
        purchaseSource: PurchaseSource,
        observesPurchases: Boolean,
        demand: Demand,
    ) = InternalSuperwallEvent.Transaction(
        state = InternalSuperwallEvent.Transaction.State.Restore(restoreType),
        paywallInfo = paywallInfoOf(purchaseSource),
        product = product,
        model = null,
        source =
            when (purchaseSource) {
                is PurchaseSource.Internal -> TransactionSource.INTERNAL
                is PurchaseSource.ExternalPurchase -> TransactionSource.EXTERNAL
                is PurchaseSource.ObserverMode -> TransactionSource.OBSERVER
            },
        isObserved = observesPurchases,
        demandScore = demand.score,
        demandTier = demand.tier,
    )

    fun restoreStart(paywallInfo: PaywallInfo) =
        InternalSuperwallEvent.Restore(InternalSuperwallEvent.Restore.State.Start, paywallInfo)

    fun restoreComplete(paywallInfo: PaywallInfo) =
        InternalSuperwallEvent.Restore(InternalSuperwallEvent.Restore.State.Complete, paywallInfo)

    fun restoreFailure(
        message: String,
        paywallInfo: PaywallInfo,
    ) = InternalSuperwallEvent.Restore(InternalSuperwallEvent.Restore.State.Failure(message), paywallInfo)

    /** The message logged and tracked when a restore does not leave the user entitled. */
    fun restoreFailureMessage(
        restorationResult: RestorationResult,
        hasEntitlements: Boolean,
    ): String {
        val hasRestored = restorationResult is RestorationResult.Restored
        val detail =
            if (hasRestored && !hasEntitlements) {
                " The user's subscription status is \"inactive\", but the restoration result is \"restored\"." +
                    " Ensure the subscription status is active before confirming successful restoration."
            } else {
                " Original restoration error message: ${
                    (restorationResult as? RestorationResult.Failed)?.error?.localizedMessage
                }"
            }
        return "Transactions Failed to Restore.$detail"
    }

    const val PENDING_MESSAGE = "Needs parental approval"
}
