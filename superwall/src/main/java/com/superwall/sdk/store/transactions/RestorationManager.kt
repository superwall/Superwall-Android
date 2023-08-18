package com.superwall.sdk.store.transactions

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class RestorationManager(
    private val storeKitManager: StoreKitManager,
) {
    fun tryToRestore(paywallViewController: PaywallViewController) {
        MainScope().launch {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallTransactions,
                message = "Attempting Restore"
            )

            paywallViewController.loadingState = PaywallLoadingState.LoadingPurchase()

            val restorationResult: RestorationResult =
                storeKitManager.coordinator.txnRestorer.restorePurchases()
            val hasRestored = restorationResult == RestorationResult.Restored()

            // Will always have purchaseController
//            if (!Superwall.instance.dependencyContainer.delegateAdapter.hasPurchaseController) {
//                storeKitManager.refreshReceipt()
//                if (hasRestored) {
//                    storeKitManager.loadPurchasedProducts()
//                }
//            }

            val isUserSubscribed =
                Superwall.instance.subscriptionStatus.value == SubscriptionStatus.Active

            if (hasRestored && isUserSubscribed) {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallTransactions,
                    message = "Transactions Restored"
                )
                transactionWasRestored(paywallViewController)
            } else {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallTransactions,
                    message = "Transactions Failed to Restore"
                )

                paywallViewController.presentAlert(
                    title = Superwall.instance.options.paywalls.restoreFailed.title,
                    message = Superwall.instance.options.paywalls.restoreFailed.message,
                    closeActionTitle = Superwall.instance.options.paywalls.restoreFailed.closeButtonTitle
                )
            }
        }
    }

    private fun transactionWasRestored(paywallViewController: PaywallViewController) {
        val paywallInfo = paywallViewController.info
        MainScope().launch {
            val trackedEvent = InternalSuperwallEvent.Transaction(
                state = InternalSuperwallEvent.Transaction.State.Restore(),
                paywallInfo = paywallInfo,
                product = null,
                model = null
            )
            Superwall.instance.track(trackedEvent)

            if (Superwall.instance.options.paywalls.automaticallyDismiss) {
                Superwall.instance.dismiss(paywallViewController, result = PaywallResult.Restored())
            }
        }
    }
}