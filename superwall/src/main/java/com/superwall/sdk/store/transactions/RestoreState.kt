package com.superwall.sdk.store.transactions

import com.superwall.sdk.config.options.localized
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.misc.AlertControllerFactory.AlertProps
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import kotlinx.coroutines.CompletableDeferred

/**
 * Where restores stand, owned by [RestoreFlow].
 *
 * Restores are not deduplicated (they never were), so [Restoring] counts the ones running and
 * keeps the paywall of the latest.
 */
internal sealed class RestoreState {
    /** No restore is running. */
    object Idle : RestoreState()

    /** [running] restores are under way; the latest was started for [paywallInfo] (empty outside a paywall). */
    data class Restoring(
        val paywallInfo: PaywallInfo,
        val running: Int = 1,
    ) : RestoreState()

    val isRestoring: Boolean
        get() = this is Restoring

    // -----------------------------------------------------------------------
    // Pure state mutations — (RestoreState) -> RestoreState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (RestoreState) -> RestoreState,
    ) : Reducer<RestoreState> {
        data class Begin(
            val paywallInfo: PaywallInfo,
        ) : Updates({ state ->
                when (state) {
                    Idle -> Restoring(paywallInfo)
                    is Restoring -> state.copy(paywallInfo = paywallInfo, running = state.running + 1)
                }
            })

        object Finish : Updates({ state ->
            when (state) {
                Idle -> Idle
                is Restoring -> if (state.running <= 1) Idle else state.copy(running = state.running - 1)
            }
        })
    }

    // -----------------------------------------------------------------------
    // Actions — async work via RestoreContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend RestoreContext.() -> Unit,
    ) : TypedAction<RestoreContext> {
        /**
         * A restore asked for by the app, a paywall or the Customer Center: test mode, or the
         * purchase controller's restore followed by what its [RestoreOutcome] calls for.
         * Completes [result] with what the purchase controller returned.
         */
        data class Restore(
            val paywallView: PaywallView?,
            val presentsFailureAlert: Boolean,
            val result: CompletableDeferred<RestorationResult>,
        ) : Actions(exec@{
                transactionLog(message = "Attempting Restore")

                // Test mode intercept: simulate restore without real billing
                val testMode = dependencies.testMode
                val testHandler = dependencies.testModeTransactionHandler
                if (testMode?.isTestMode == true && testHandler != null) {
                    val restored = testHandler.handleRestore()
                    if (restored is RestorationResult.Restored) {
                        dependencies.setSubscriptionStatus?.invoke(testMode.buildSubscriptionStatus())
                    }
                    result.complete(restored)
                    return@exec
                }

                val paywallInfo = paywallView?.state?.info ?: PaywallInfo.empty()
                update(Updates.Begin(paywallInfo))
                try {
                    paywallView?.updateState(PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.LoadingPurchase))
                    dependencies.track(TransactionAnalytics.restoreStart(paywallInfo))
                    val restorationResult = dependencies.purchaseController.restorePurchases()

                    val status = dependencies.subscriptionStatus()
                    val webEntitlements = dependencies.webEntitlements()
                    dependencies.storeManager.loadPurchasedProducts(dependencies.allEntitlementsByProductId())
                    val paywallEntitlements =
                        paywallView
                            ?.state
                            ?.paywall
                            ?.productIds
                            ?.flatMap { dependencies.entitlementsById(it) }
                            .orEmpty()

                    val outcome =
                        RestoreOutcome.of(
                            restorationResult = restorationResult,
                            status = status,
                            webEntitlements = webEntitlements,
                            paywallEntitlements = paywallEntitlements,
                            webToAppEnabled = dependencies.factory.isWebToAppEnabled(),
                        )
                    runInline(Settle(outcome, paywallView, presentsFailureAlert))
                    result.complete(restorationResult)
                } finally {
                    update(Updates.Finish)
                }
            })

        /** Acts on a finished restore: tracks it, and dismisses, prompts or alerts as [outcome] calls for. */
        data class Settle(
            val outcome: RestoreOutcome,
            val paywallView: PaywallView?,
            val presentsFailureAlert: Boolean,
        ) : Actions({
                val paywallInfo = paywallView?.state?.info ?: PaywallInfo.empty()
                when (outcome) {
                    RestoreOutcome.Restored -> {
                        transactionLog(message = "Transactions Restored")
                        dependencies.track(TransactionAnalytics.restoreComplete(paywallInfo))
                        if (paywallView != null) {
                            runInline(Restored(null, PurchaseSource.Internal("", paywallView.state)))
                        }
                    }

                    RestoreOutcome.MissingWebEntitlements -> {
                        paywallView?.updateState(PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready))
                        if (presentsFailureAlert) runInline(AskToRestoreFromWeb)
                    }

                    is RestoreOutcome.Failed -> {
                        transactionLog(message = outcome.message)
                        dependencies.track(TransactionAnalytics.restoreFailure(outcome.message, paywallInfo))
                        if (presentsFailureAlert) {
                            if (outcome.offerWebRestore) {
                                runInline(AskToRestoreFromWeb)
                            } else {
                                runInline(ShowFailureAlert(paywallView))
                            }
                        }
                    }
                }
            })

        /**
         * A restore went through: tracks it, dismisses the paywall when the options ask for it
         * and tells the backend about the receipts. [product] is set when a purchase of that
         * product turned out to be a restore (observer mode).
         */
        data class Restored(
            val product: StoreProduct?,
            val source: PurchaseSource,
        ) : Actions({
                val restoreType: RestoreType =
                    if (product != null) {
                        // A product means it came from a purchase of that specific product.
                        RestoreType.ViaPurchase(dependencies.awaitLatestTransaction())
                    } else {
                        RestoreType.ViaRestore
                    }

                dependencies.track(
                    TransactionAnalytics.restored(
                        product = product,
                        restoreType = restoreType,
                        purchaseSource = source,
                        observesPurchases = dependencies.observesPurchases,
                        demand = dependencies.demand(),
                    ),
                )

                if (dependencies.factory.makeSuperwallOptions().paywalls.automaticallyDismiss &&
                    source is PurchaseSource.Internal
                ) {
                    dependencies.dismiss(source.paywallInfo.cacheKey, PaywallResult.Restored())
                }

                dependencies.ioScope.launchWithTracking { dependencies.notifyBackendOfReceipts() }
            })

        /** The SDK's own alert for a failed restore, on the paywall that started it. */
        data class ShowFailureAlert(
            val paywallView: PaywallView?,
        ) : Actions({
                paywallView?.let { view ->
                    // Defaults are translated to the paywall's locale; custom wording is kept.
                    val alert =
                        dependencies.factory
                            .makeSuperwallOptions()
                            .paywalls.restoreFailed
                            .localized(view.context)
                    view.showAlert(
                        title = alert.title,
                        message = alert.message,
                        closeActionTitle = alert.closeButtonTitle,
                    )
                }
            })

        /** Offers to look for more subscriptions on the web, worded for whether Play restored any. */
        object AskToRestoreFromWeb : Actions({
            dependencies.showAlert(webRestorePrompt(dependencies.activeDeviceEntitlements().isNotEmpty()) {
                dependencies.openRestoreOnWeb()
            })
        })
    }
}

/** What a finished restore calls for, decided from what the user is entitled to afterwards. */
internal sealed class RestoreOutcome {
    /** The restore went through and covers what the paywall offers. */
    object Restored : RestoreOutcome()

    /** The restore went through but the paywall's entitlements may be on the web: offer to look there. */
    object MissingWebEntitlements : RestoreOutcome()

    /** Nothing was restored. [offerWebRestore] when web checkout could hold the subscription instead. */
    data class Failed(
        val message: String,
        val offerWebRestore: Boolean,
    ) : RestoreOutcome()

    companion object {
        fun of(
            restorationResult: RestorationResult,
            status: SubscriptionStatus,
            webEntitlements: Set<Entitlement>,
            paywallEntitlements: List<Entitlement>,
            webToAppEnabled: Boolean,
        ): RestoreOutcome {
            val hasEntitlements = status is SubscriptionStatus.Active || webEntitlements.isNotEmpty()
            if (restorationResult !is RestorationResult.Restored || !hasEntitlements) {
                return Failed(
                    message = TransactionAnalytics.restoreFailureMessage(restorationResult, hasEntitlements),
                    offerWebRestore = webToAppEnabled,
                )
            }
            val existing = ((status as? SubscriptionStatus.Active)?.entitlements ?: emptySet()) + webEntitlements
            return if (existing.containsAll(paywallEntitlements) || !webToAppEnabled) Restored else MissingWebEntitlements
        }
    }
}

/** The restore-on-web prompt; [hasDeviceEntitlements] when Play already restored something. */
internal fun webRestorePrompt(
    hasDeviceEntitlements: Boolean,
    openRestoreOnWeb: () -> Unit,
) = AlertProps(
    title = if (hasDeviceEntitlements) "Restore via the web?" else "No Subscription Found",
    message =
        if (hasDeviceEntitlements) {
            "Your Play Store subscriptions were restored. Would you like to check for more on the web?"
        } else {
            "No Play Store subscription found, would you like to check on the web?"
        },
    closeActionTitle = "Close",
    actionTitle = "Yes",
    action = { openRestoreOnWeb() },
)
