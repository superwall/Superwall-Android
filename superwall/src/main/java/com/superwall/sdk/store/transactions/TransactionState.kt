package com.superwall.sdk.store.transactions

import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.analytics.superwall.SuperwallEvents
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.AlertControllerFactory.AlertProps
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.request.PaywallLogic
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.storage.PurchasingProductdIds
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/**
 * State of the purchases the SDK has started and not yet resolved, owned by [PurchaseFlow].
 *
 * [inFlight] is keyed by an id made for each purchase attempt, so two attempts on the same
 * product never clear each other. [purchasingProductIds] holds the ids persisted under
 * [PurchasingProductdIds] as of their last update; storage stays the source, as before.
 */
internal data class TransactionState(
    val inFlight: Map<String, InFlightPurchase> = emptyMap(),
    val purchasingProductIds: Set<String> = emptySet(),
) {
    val isPurchasing: Boolean
        get() = inFlight.values.any { it is InFlightPurchase.Purchasing }

    /** True while a purchase started from a paywall runs; a second paywall purchase is turned away. */
    val isPurchasingFromPaywall: Boolean
        get() =
            inFlight.values.any {
                it is InFlightPurchase.Purchasing && it.source is PurchaseSource.Internal
            }

    // -----------------------------------------------------------------------
    // Pure state mutations — (TransactionState) -> TransactionState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (TransactionState) -> TransactionState,
    ) : Reducer<TransactionState> {
        /** Reserves [attemptId]; a pending purchase of the same product is superseded by it. */
        data class Begin(
            val attemptId: String,
            val product: StoreProduct,
            val source: PurchaseSource,
        ) : Updates({ state ->
                val withoutSupersededPending =
                    state.inFlight.filterValues {
                        !(it is InFlightPurchase.Pending && it.product.fullIdentifier == product.fullIdentifier)
                    }
                state.copy(
                    inFlight = withoutSupersededPending + (attemptId to InFlightPurchase.Purchasing(product, source)),
                )
            })

        /** The store holds the purchase for approval: [attemptId] stays, as pending. */
        data class MarkPending(
            val attemptId: String,
        ) : Updates({ state ->
                val current = state.inFlight[attemptId]
                if (current == null) {
                    state
                } else {
                    state.copy(
                        inFlight =
                            state.inFlight +
                                (attemptId to InFlightPurchase.Pending(current.product, current.source)),
                    )
                }
            })

        /** The attempt ended. A pending one stays until a new purchase of its product. */
        data class Finish(
            val attemptId: String,
        ) : Updates({ state ->
                if (state.inFlight[attemptId] is InFlightPurchase.Purchasing) {
                    state.copy(inFlight = state.inFlight - attemptId)
                } else {
                    state
                }
            })

        data class SetPurchasingProductIds(
            val ids: Set<String>,
        ) : Updates({ state -> state.copy(purchasingProductIds = ids) })
    }

    // -----------------------------------------------------------------------
    // Actions — async work via TransactionContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend TransactionContext.() -> Unit,
    ) : TypedAction<TransactionContext> {
        /**
         * Runs on the serial queue, so two taps can't both pass: turns away a paywall purchase
         * while another runs, resolves the product and reserves the attempt. The purchase itself
         * runs afterwards, off the queue, as [Run].
         */
        data class Admit(
            val source: PurchaseSource,
            val onAdmitted: () -> Unit,
            val admission: CompletableDeferred<PurchaseAdmission>,
        ) : Actions(exec@{
                if (source is PurchaseSource.Internal && state.value.isPurchasingFromPaywall) {
                    transactionLog(message = "Ignoring a purchase of ${source.productId}: another paywall purchase is running.")
                    admission.complete(PurchaseAdmission.Rejected)
                    return@exec
                }
                onAdmitted()

                val product =
                    when (source) {
                        is PurchaseSource.Internal ->
                            dependencies.storeManager.getProductFromCache(source.productId) ?: run {
                                transactionLog(
                                    LogLevel.error,
                                    "Trying to purchase (${source.productId}) but the product has failed to load. Visit https://superwall.com/l/missing-products to diagnose.",
                                )
                                admission.complete(PurchaseAdmission.ProductMissing)
                                return@exec
                            }

                        is PurchaseSource.ExternalPurchase -> source.product
                        is PurchaseSource.ObserverMode -> source.product
                    }

                val attemptId = UUID.randomUUID().toString()
                update(Updates.Begin(attemptId, product, source))
                admission.complete(PurchaseAdmission.Admitted(attemptId, product))
            })

        /**
         * The purchase of an admitted attempt: test mode, a custom product through the purchase
         * controller, or Play. Completes [result] with what the purchase controller returned.
         */
        data class Run(
            val attemptId: String,
            val product: StoreProduct,
            val source: PurchaseSource,
            val shouldDismiss: Boolean,
            val result: CompletableDeferred<PurchaseResult>,
        ) : Actions(exec@{
                val testMode = dependencies.testMode
                val testHandler = dependencies.testModeTransactionHandler
                if (testMode?.isTestMode == true && testHandler != null) {
                    runInline(PrepareToPurchase(product, source))
                    val outcome = testHandler.handlePurchase(product, source)
                    when (outcome) {
                        is PurchaseResult.Purchased -> {
                            // No real receipt to verify, so set the status directly.
                            dependencies.setSubscriptionStatus?.invoke(testMode.buildSubscriptionStatus())
                            trackTransactionDidSucceed(null, product, source, product.hasFreeTrial)
                            if (shouldDismiss &&
                                source is PurchaseSource.Internal &&
                                dependencies.factory.makeSuperwallOptions().paywalls.automaticallyDismiss
                            ) {
                                dependencies.dismiss(
                                    source.paywallInfo.cacheKey,
                                    PaywallResult.Purchased(product.fullIdentifier),
                                )
                            }
                        }

                        else -> runInline(Settle(attemptId, product, source, outcome.toOutcome(), shouldDismiss))
                    }
                    result.complete(outcome)
                    return@exec
                }

                if (product.isCustomProduct) {
                    runInline(RunCustomProduct(attemptId, product, source, shouldDismiss, result))
                    return@exec
                }

                val rawStoreProduct =
                    product.rawStoreProduct ?: run {
                        result.complete(PurchaseResult.Failed("Missing raw store product for ${product.fullIdentifier}"))
                        return@exec
                    }
                transactionLog(message = "!!! Purchasing product ${rawStoreProduct.hasFreeTrial}")
                val activity =
                    dependencies.activityProvider.getCurrentActivity() ?: run {
                        result.complete(PurchaseResult.Failed("Activity not found - required for starting the billing flow"))
                        return@exec
                    }

                runInline(PrepareToPurchase(product, source))
                val tokensBeforePurchase = dependencies.storeManager.knownPurchaseTokens
                val outcome =
                    resolveReplacement(product, source).fold(
                        onSuccess = { replacement ->
                            dependencies.storeManager.purchaseController.purchase(
                                activity = activity,
                                product = product,
                                offerId = rawStoreProduct.offerId,
                                basePlanId = rawStoreProduct.basePlanId,
                                replacement = replacement,
                            )
                        },
                        onFailure = { replacementLookupFailed(product) },
                    )

                // Play's purchase only names the subscription, so remember which base plan was bought.
                if (outcome is PurchaseResult.Purchased) {
                    dependencies.storeManager.recordPurchasedProduct(product, tokensBeforePurchase)
                }

                // An external-only controller owns the rest of the purchase's lifecycle.
                if (!isExternalOnly(source)) {
                    val isEligibleForTrial = rawStoreProduct.selectedOffer != null
                    val settled =
                        when (outcome) {
                            is PurchaseResult.Purchased ->
                                PurchaseOutcome.Purchased(didStartFreeTrial = isEligibleForTrial && product.hasFreeTrial)

                            else -> outcome.toOutcome()
                        }
                    runInline(Settle(attemptId, product, source, settled, shouldDismiss))
                }
                result.complete(outcome)
            })

        /**
         * Purchase of a custom (store == CUSTOM) product. It needs an external purchase
         * controller, gets a UUID transaction id per attempt and never touches Play receipts.
         */
        data class RunCustomProduct(
            val attemptId: String,
            val product: StoreProduct,
            val source: PurchaseSource,
            val shouldDismiss: Boolean,
            val result: CompletableDeferred<PurchaseResult>,
        ) : Actions(exec@{
                val factory = dependencies.factory
                if (!factory.makeHasExternalPurchaseController()) {
                    val message =
                        "Custom product \"${product.fullIdentifier}\" can only be purchased using a " +
                            "PurchaseController. Set one via Superwall.configure(..., purchaseController:) " +
                            "and handle products where isCustomProduct == true in " +
                            "purchase(activity, product, basePlanId, offerId)."
                    transactionLog(message = message, error = Error(message))
                    runInline(Settle(attemptId, product, source, PurchaseOutcome.Failed(message), shouldDismiss))
                    result.complete(PurchaseResult.Failed(message))
                    return@exec
                }

                // Local copy keeps this flow consistent even if a concurrent purchase overwrites the
                // shared product's field; product.customTransactionId is set for the dev-facing contract.
                val txnId = UUID.randomUUID().toString()
                product.customTransactionId = txnId

                // Entitlement-history-based eligibility, not raw trial metadata: a repeat purchaser who
                // already used their trial is charged immediately and must not emit freeTrialStart.
                val didStartFreeTrial =
                    PaywallLogic.isFreeTrialEligibleForCustomProduct(
                        product = product,
                        entitlements = dependencies.entitlementsById(product.fullIdentifier),
                        customerInfo = dependencies.currentCustomerInfo(),
                    )

                runInline(PrepareToPurchase(product, source))

                // PrepareToPurchase skips the Start event for an external-only controller, but a
                // custom purchase is otherwise invisible to Superwall: emit Start so it pairs with
                // the Complete tracked on success and transaction funnels stay balanced.
                val externalOnly = isExternalOnly(source)
                if (externalOnly) {
                    dependencies.track(TransactionAnalytics.start(product, source, dependencies.demand()))
                }

                val activity =
                    dependencies.activityProvider.getCurrentActivity() ?: run {
                        result.complete(PurchaseResult.Failed("Activity not found - required for starting the purchase flow"))
                        return@exec
                    }
                val outcome =
                    resolveReplacement(product, source).fold(
                        onSuccess = { replacement ->
                            dependencies.storeManager.purchaseController.purchase(
                                activity = activity,
                                product = product,
                                basePlanId = null,
                                offerId = null,
                                replacement = replacement,
                            )
                        },
                        onFailure = { replacementLookupFailed(product) },
                    )

                suspend fun transaction() =
                    factory.makeStoreTransaction(
                        customTransactionId = txnId,
                        productIdentifier = product.fullIdentifier,
                        purchaseDate = Date(),
                    )
                if (externalOnly) {
                    // The developer's flow owns the rest; still track the transaction so analytics
                    // include the custom transaction id.
                    if (outcome is PurchaseResult.Purchased) {
                        trackTransactionDidSucceed(transaction(), product, source, didStartFreeTrial)
                    }
                } else {
                    val settled =
                        when (outcome) {
                            is PurchaseResult.Purchased ->
                                PurchaseOutcome.PurchasedWithoutReceipt(transaction(), didStartFreeTrial)

                            else -> outcome.toOutcome()
                        }
                    runInline(Settle(attemptId, product, source, settled, shouldDismiss))
                }
                result.complete(outcome)
            })

        /**
         * Tracks the start of a purchase. Also run for purchases the app makes itself in observer
         * mode, so the observer-mode tracker learns about them.
         */
        data class PrepareToPurchase(
            val product: StoreProduct,
            val source: PurchaseSource,
        ) : Actions(exec@{
                // The billing flow retains the last Play result indefinitely; clear it so this
                // purchase can't be resolved against a stale transaction from an earlier one.
                dependencies.storeManager.billing.purchaseResults.value = null

                when (source) {
                    is PurchaseSource.Internal -> {
                        scope.launch { transactionLog(message = "Transaction Purchasing", info = mapOf("paywall_vc" to source)) }
                        dependencies.track(TransactionAnalytics.start(product, source, dependencies.demand()))
                    }

                    is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
                        if (source is PurchaseSource.ObserverMode && product.rawStoreProduct != null) {
                            onObservedPurchaseStarted(ObservedPurchase(product.fullIdentifier, product))
                        }
                        val storeManager = dependencies.storeManager
                        if (!storeManager.hasCached(product.fullIdentifier)) {
                            storeManager.cacheProduct(product.fullIdentifier, product)
                        }

                        val factory = dependencies.factory
                        if (factory.makeHasExternalPurchaseController() && !factory.makeHasInternalPurchaseController()) {
                            return@exec
                        }
                        // With an external purchase controller the controller's purchase function
                        // reports the start instead.
                        if (!factory.makeSuperwallOptions().shouldObservePurchases &&
                            factory.makeHasExternalPurchaseController()
                        ) {
                            return@exec
                        }

                        scope.launch { transactionLog(message = "External Transaction Purchasing") }
                        dependencies.track(TransactionAnalytics.start(product, source, dependencies.demand()))
                    }
                }
            })

        /**
         * Acts on how a purchase ended: tracks it, and for a paywall purchase updates, dismisses
         * or alerts on the paywall. Purchases observed in observer mode settle here too.
         */
        data class Settle(
            val attemptId: String?,
            val product: StoreProduct,
            val source: PurchaseSource,
            val outcome: PurchaseOutcome,
            val shouldDismiss: Boolean,
        ) : Actions({
                when (outcome) {
                    is PurchaseOutcome.Purchased -> didPurchase(product, source, outcome, shouldDismiss)
                    is PurchaseOutcome.PurchasedWithoutReceipt ->
                        didPurchaseWithoutReceipt(product, source, outcome, shouldDismiss)
                    is PurchaseOutcome.Failed -> didFail(outcome.message, product, source)
                    PurchaseOutcome.Cancelled -> didCancel(product, source)
                    PurchaseOutcome.Pending -> {
                        attemptId?.let { update(Updates.MarkPending(it)) }
                        didPend(source)
                    }
                }
            })

        /** Releases [attemptId]; queued after the purchase so the next paywall purchase can start. */
        data class Finish(
            val attemptId: String,
        ) : Actions({ update(Updates.Finish(attemptId)) })

        /** Removes finished purchases from the persisted purchasing product ids. */
        data class ClearPurchasingIds(
            val removed: List<String>,
        ) : Actions({
                val remaining = (read(PurchasingProductdIds).getOrNull() ?: emptySet()) - removed.toSet()
                update(Updates.SetPurchasingProductIds(remaining))
                persist(PurchasingProductdIds, remaining)
            })
    }
}

/** One purchase the SDK is waiting on, typed by where it stands. */
internal sealed class InFlightPurchase {
    abstract val product: StoreProduct
    abstract val source: PurchaseSource

    /** The store's purchase sheet is open, or the purchase controller is still working. */
    data class Purchasing(
        override val product: StoreProduct,
        override val source: PurchaseSource,
    ) : InFlightPurchase()

    /** The store accepted the purchase but holds it for approval, such as a parent's. */
    data class Pending(
        override val product: StoreProduct,
        override val source: PurchaseSource,
    ) : InFlightPurchase()
}

/** Whether a purchase may start, decided on the purchase actor's queue. */
internal sealed class PurchaseAdmission {
    data class Admitted(
        val attemptId: String,
        val product: StoreProduct,
    ) : PurchaseAdmission()

    /** A paywall purchase is already running. */
    object Rejected : PurchaseAdmission()

    /** The paywall's product never loaded. */
    object ProductMissing : PurchaseAdmission()
}

/** How a purchase ended, as the SDK acts on it. */
internal sealed class PurchaseOutcome {
    /** Bought through Play: the receipt and latest transaction are read back. */
    data class Purchased(
        val didStartFreeTrial: Boolean,
        /** The store purchase, when known up front (observer mode). */
        val purchase: StorePurchase? = null,
    ) : PurchaseOutcome()

    /** Bought outside Play (a custom product): the SDK built [transaction] itself. */
    data class PurchasedWithoutReceipt(
        val transaction: StoreTransaction,
        val didStartFreeTrial: Boolean,
    ) : PurchaseOutcome()

    data class Failed(
        val message: String,
    ) : PurchaseOutcome()

    object Cancelled : PurchaseOutcome()

    object Pending : PurchaseOutcome()
}

/** Maps every result but a purchase, which needs more facts to settle. */
internal fun PurchaseResult.toOutcome(): PurchaseOutcome =
    when (this) {
        is PurchaseResult.Purchased -> PurchaseOutcome.Purchased(didStartFreeTrial = false)
        is PurchaseResult.Failed -> PurchaseOutcome.Failed(errorMessage)
        is PurchaseResult.Cancelled -> PurchaseOutcome.Cancelled
        is PurchaseResult.Pending -> PurchaseOutcome.Pending
    }

// ---------------------------------------------------------------------------
// Steps the actions share. They read the context and do no state changes of their own.
// ---------------------------------------------------------------------------

private suspend fun TransactionContext.didPurchase(
    product: StoreProduct,
    source: PurchaseSource,
    outcome: PurchaseOutcome.Purchased,
    shouldDismiss: Boolean,
) {
    val storeManager = dependencies.storeManager
    when (source) {
        is PurchaseSource.Internal -> {
            scope.launch {
                transactionLog(
                    message = "Transaction Succeeded",
                    info = mapOf("product_id" to product.fullIdentifier, "paywall_state" to source.state),
                )
            }
            val transaction = awaitLatestTransaction()
            storeManager.loadPurchasedProducts(dependencies.allEntitlementsByProductId())
            dependencies.ioScope.launchWithTracking { dependencies.notifyBackendOfReceipts() }
            trackTransactionDidSucceed(transaction, product, source, outcome.didStartFreeTrial)
            dismissOrReady(product, source, shouldDismiss)
        }

        is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
            transactionLog(message = "Transaction Succeeded", info = mapOf("product_id" to product.fullIdentifier))
            val transaction =
                if (source is PurchaseSource.ExternalPurchase) {
                    outcome.purchase?.let { dependencies.storeTransactionOf(it) } ?: awaitLatestTransaction()
                } else {
                    null
                }
            storeManager.loadPurchasedProducts(dependencies.allEntitlementsByProductId())
            dependencies.ioScope.launchWithTracking { dependencies.notifyBackendOfReceipts() }
            trackTransactionDidSucceed(transaction, product, source, outcome.didStartFreeTrial)
        }
    }
}

private suspend fun TransactionContext.didPurchaseWithoutReceipt(
    product: StoreProduct,
    source: PurchaseSource,
    outcome: PurchaseOutcome.PurchasedWithoutReceipt,
    shouldDismiss: Boolean,
) {
    // No Play receipt for custom products, so purchased products aren't reloaded.
    trackTransactionDidSucceed(outcome.transaction, product, source, outcome.didStartFreeTrial)
    if (source is PurchaseSource.Internal) dismissOrReady(product, source, shouldDismiss)
}

private suspend fun TransactionContext.dismissOrReady(
    product: StoreProduct,
    source: PurchaseSource.Internal,
    shouldDismiss: Boolean,
) {
    if (shouldDismiss && dependencies.factory.makeSuperwallOptions().paywalls.automaticallyDismiss) {
        dependencies.dismiss(source.paywallInfo.cacheKey, PaywallResult.Purchased(product.fullIdentifier))
    } else {
        dependencies.updateState(
            source.paywallInfo.cacheKey,
            PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready),
        )
    }
}

private suspend fun TransactionContext.didFail(
    message: String,
    product: StoreProduct,
    source: PurchaseSource,
) {
    val event =
        TransactionAnalytics.failure(
            errorMessage = message,
            product = product,
            purchaseSource = source,
            observesPurchases = dependencies.observesPurchases,
            demand = dependencies.demand(),
        )
    when (source) {
        is PurchaseSource.Internal -> {
            transactionLog(
                message = "Transaction Error: $message",
                info = mapOf("product_id" to product.fullIdentifier, "paywall_state" to source.state),
            )
            dependencies.ioScope.launchWithTracking { dependencies.track(event) }

            val factory = dependencies.factory
            val transactionFailExists = factory.makeTriggers().contains(SuperwallEvents.TransactionFail.rawName)
            if (factory.makeSuperwallOptions().paywalls.shouldShowPurchaseFailureAlert && !transactionFailExists) {
                scope.launch {
                    transactionLog(
                        message = "Transaction Error",
                        info = mapOf("product_id" to product.fullIdentifier, "paywall_state" to source.state),
                        error = Error(message),
                    )
                }
                dependencies.showAlert(AlertProps("An error occurred", message))
            } else {
                dependencies.updateState(
                    source.paywallInfo.cacheKey,
                    PaywallViewState.Updates.ToggleSpinner(hidden = true),
                )
            }
        }

        is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
            transactionLog(
                message = "Transaction Error: $message",
                info = mapOf("product_id" to product.fullIdentifier),
                error = Error(message),
            )
            scope.launch { dependencies.track(event) }
        }
    }
}

private suspend fun TransactionContext.didCancel(
    product: StoreProduct,
    source: PurchaseSource,
) {
    when (source) {
        is PurchaseSource.Internal -> {
            scope.launch {
                transactionLog(
                    message = "Transaction Abandoned",
                    info = mapOf("product_id" to product.fullIdentifier, "paywall_state" to source.state),
                )
            }
            val cacheKey = source.paywallInfo.cacheKey
            dependencies.track(TransactionAnalytics.abandon(product, source, dependencies.demand()))
            dependencies.notifyOfTransactionAbandon(cacheKey)
            dependencies.updateState(cacheKey, PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready))
        }

        is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
            scope.launch {
                transactionLog(message = "Transaction Abandoned", info = mapOf("product_id" to product.fullIdentifier))
            }
            dependencies.track(TransactionAnalytics.abandon(product, source, dependencies.demand()))
        }
    }
}

private suspend fun TransactionContext.didPend(source: PurchaseSource) {
    val event =
        TransactionAnalytics.pending(
            purchaseSource = source,
            observesPurchases = dependencies.observesPurchases,
            demand = dependencies.demand(),
        )
    when (source) {
        is PurchaseSource.Internal -> {
            scope.launch {
                transactionLog(message = "Transaction Pending", info = mapOf("paywall" to source.paywallInfo))
            }
            dependencies.track(event)
            dependencies.showAlert(
                AlertProps(
                    "Waiting for Approval",
                    "Thank you! This purchase is pending approval from your parent. Please try again once it is approved.",
                ),
            )
        }

        is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
            scope.launch { transactionLog(message = "Transaction Pending") }
            dependencies.track(event)
        }
    }
}

internal suspend fun TransactionContext.trackTransactionDidSucceed(
    transaction: StoreTransaction?,
    product: StoreProduct,
    source: PurchaseSource,
    didStartFreeTrial: Boolean,
) {
    dependencies.track(
        TransactionAnalytics.complete(
            product = product,
            transaction = transaction,
            purchaseSource = source,
            demand = dependencies.demand(),
            userAttributes = dependencies.factory.getCurrentUserAttributes(),
            storefrontCountryCode = dependencies.storeManager.storefrontCountryCode,
        ),
    )
    dependencies.eventsQueue.flushInternal()
    if (source is PurchaseSource.Internal) {
        // Only report a trial end when a trial actually started; product metadata alone
        // can describe a trial the user was not eligible for.
        val trialEnd = if (didStartFreeTrial) product.trialPeriodEndDate?.time else null
        // Notify the paywall for every internal purchase, including one-time products,
        // so it can run its post-purchase actions.
        dependencies.notifyOfTransactionComplete(
            source.paywallInfo.cacheKey,
            trialEnd,
            product.fullIdentifier,
            didStartFreeTrial,
        )
    }
    dependencies.track(TransactionAnalytics.purchaseKind(product, source, didStartFreeTrial))
}

private fun TransactionContext.isExternalOnly(source: PurchaseSource): Boolean {
    val factory = dependencies.factory
    return source is PurchaseSource.ExternalPurchase &&
        factory.makeHasExternalPurchaseController() &&
        !factory.makeHasInternalPurchaseController()
}

private fun replacementLookupFailed(product: StoreProduct): PurchaseResult =
    PurchaseResult.Failed(
        "Couldn't check your current subscription, so ${product.productIdentifier} wasn't purchased. " +
            "Please try again.",
    )

internal fun transactionLog(
    logLevel: LogLevel = LogLevel.debug,
    message: String,
    info: Map<String, Any>? = null,
    error: Throwable? = null,
) = Logger.debug(
    logLevel = logLevel,
    scope = LogScope.paywallTransactions,
    message = message,
    info = info,
    error = error,
)
