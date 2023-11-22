package com.superwall.sdk.store.transactions

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventObjc
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.IdentityInfoFactory
import com.superwall.sdk.dependencies.LocaleIdentifierFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.dependencies.TransactionVerifierFactory
import com.superwall.sdk.dependencies.TriggerFactory
import com.superwall.sdk.misc.ActivityLifecycleTracker
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.*

class TransactionManager(
    private val storeKitManager: StoreKitManager,
    private val sessionEventsManager: SessionEventsManager,
    private val activityProvider: ActivityProvider,
    private val factory: Factory
) {
    interface Factory: OptionsFactory, TriggerFactory, TransactionVerifierFactory, StoreTransactionFactory {}
    private var lastPaywallViewController: PaywallViewController? = null

    suspend fun purchase(productId: String, paywallViewController: PaywallViewController) {
        val product = storeKitManager.productsById[productId] ?: return

        val activity = activityProvider.getCurrentActivity() ?: return

        prepareToStartTransaction(product, paywallViewController)

        val result = storeKitManager.purchaseController.purchase(
            activity,
            product.rawStoreProduct.underlyingSkuDetails
        )

        when (result) {
            is PurchaseResult.Purchased -> {
                didPurchase(product, paywallViewController)
            }
            is PurchaseResult.Failed -> {
                val superwallOptions = factory.makeSuperwallOptions()
                val shouldShowPurchaseFailureAlert = superwallOptions.paywalls.shouldShowPurchaseFailureAlert
                val triggers = factory.makeTriggers()
                val transactionFailExists = triggers.contains(SuperwallEventObjc.TransactionFail.rawName)

                if (shouldShowPurchaseFailureAlert && !transactionFailExists) {
                    trackFailure(
                        result.error,
                        product,
                        paywallViewController
                    )
                    presentAlert(
                        Error(result.error),
                        product,
                        paywallViewController
                    )
                } else {
                    trackFailure(
                        result.error,
                        product,
                        paywallViewController
                    )
                    return paywallViewController.togglePaywallSpinner(isHidden = true)
                }
            }
            is PurchaseResult.Pending -> {
                handlePendingTransaction(paywallViewController)
            }
            is PurchaseResult.Cancelled -> {
                trackCancelled(product, paywallViewController)
            }
        }
    }

    private fun trackFailure(
        error: Throwable,
        product: StoreProduct,
        paywallViewController: PaywallViewController
    ) {
        // Log the error
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallTransactions,
            message = "Transaction Error",
            info = mapOf(
                "product_id" to product.productIdentifier,
                "paywall_vc" to paywallViewController
            ),
            error = error
        )

        // Launch a coroutine to handle async tasks
        CoroutineScope(Dispatchers.Default).launch {
            val paywallInfo = paywallViewController.info
            val trackedEvent = InternalSuperwallEvent.Transaction(
                state =  InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Failure(error.localizedMessage, product)),
                paywallInfo = paywallInfo,
                product = product,
                model = null
            )

            // Assuming Superwall.shared.track and sessionEventsManager.triggerSession.trackTransactionError are suspend functions
            Superwall.instance.track(trackedEvent)
        }
    }

    private suspend fun prepareToStartTransaction(
        product: StoreProduct,
        paywallViewController: PaywallViewController
    ) {
        GlobalScope.launch(Dispatchers.Default) {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Purchasing",
                mapOf("paywall_vc" to paywallViewController),
                null
            )
        }

        val paywallInfo = paywallViewController.info
        val trackedEvent = InternalSuperwallEvent.Transaction(
            InternalSuperwallEvent.Transaction.State.Start(product),
            paywallInfo,
            product,
            null
        )
        Superwall.instance.track(trackedEvent)

        withContext(Dispatchers.Main) {
            paywallViewController.loadingState = PaywallLoadingState.LoadingPurchase()
        }

        lastPaywallViewController = paywallViewController
    }

    // ... Remaining functions translated in a similar fashion ...
    private suspend fun didPurchase(
        product: StoreProduct,
        paywallViewController: PaywallViewController
    ) {
        GlobalScope.launch(Dispatchers.Default) {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Succeeded",
                mapOf(
                    "product_id" to product.productIdentifier,
                    "paywall_vc" to paywallViewController
                ),
                null
            )
        }

        val transactionVerifier = factory.makeTransactionVerifier()
        val transaction = transactionVerifier.getLatestTransaction(
            factory = factory
        )

        transaction?.let {
            sessionEventsManager.enqueue(it)
        }

        storeKitManager.loadPurchasedProducts()

        trackTransactionDidSucceed(transaction, product)

        if (Superwall.instance.options.paywalls.automaticallyDismiss) {
            Superwall.instance.dismiss(
                paywallViewController,
                PaywallResult.Purchased(product.productIdentifier)
            )
        }
    }

    private suspend fun trackCancelled(
        product: StoreProduct,
        paywallViewController: PaywallViewController
    ) {
        GlobalScope.launch(Dispatchers.Default) {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Abandoned",
                mapOf(
                    "product_id" to product.productIdentifier,
                    "paywall_vc" to paywallViewController
                ),
                null
            )
        }

        val paywallInfo = paywallViewController.info
        val trackedEvent = InternalSuperwallEvent.Transaction(
            InternalSuperwallEvent.Transaction.State.Abandon(product),
            paywallInfo,
            product,
            null
        )
        Superwall.instance.track(trackedEvent)

        withContext(Dispatchers.Main) {
            paywallViewController.loadingState = PaywallLoadingState.Ready()
        }
    }

    private suspend fun handlePendingTransaction(paywallViewController: PaywallViewController) {
        GlobalScope.launch(Dispatchers.Default) {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Pending",
                mapOf("paywall_vc" to paywallViewController),
                null
            )
        }

        val paywallInfo = paywallViewController.info

        val trackedEvent = InternalSuperwallEvent.Transaction(
            InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Pending("Needs parental approval")),
            paywallInfo,
            null,
            null
        )
        Superwall.instance.track(trackedEvent)

        paywallViewController.presentAlert(
            "Waiting for Approval",
            "Thank you! This purchase is pending approval from your parent. Please try again once it is approved."
        )
    }

    private suspend fun presentAlert(
        error: Error,
        product: StoreProduct,
        paywallViewController: PaywallViewController
    ) {
        GlobalScope.launch(Dispatchers.Default) {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Error",
                mapOf(
                    "product_id" to product.productIdentifier,
                    "paywall_vc" to paywallViewController
                ),
                error
            )
        }

        val paywallInfo = paywallViewController.info

        val trackedEvent = InternalSuperwallEvent.Transaction(
            InternalSuperwallEvent.Transaction.State.Fail(
                TransactionError.Failure(
                    error.message ?: "", product
                )
            ),
            paywallInfo,
            product,
            null
        )
        Superwall.instance.track(trackedEvent)

        paywallViewController.presentAlert(
            "An error occurred",
            error.message ?: "Unknown error"
        )
    }

    // ... and so on for the other methods ...
    private suspend fun trackTransactionDidSucceed(
        transaction: StoreTransaction?,
        product: StoreProduct
    ) {
        val paywallViewController = lastPaywallViewController ?: return

        val paywallShowingFreeTrial = paywallViewController.paywall.isFreeTrialAvailable == true
        val didStartFreeTrial = product.hasFreeTrial && paywallShowingFreeTrial

        val paywallInfo = paywallViewController.info

        val trackedEvent = InternalSuperwallEvent.Transaction(
            InternalSuperwallEvent.Transaction.State.Complete(product, transaction),
            paywallInfo,
            product,
            transaction
        )
        Superwall.instance.track(trackedEvent)

        if (product.subscriptionPeriod == null) {
            val nonRecurringEvent = InternalSuperwallEvent.NonRecurringProductPurchase(
                paywallInfo,
                product
            )
            Superwall.instance.track(nonRecurringEvent)
        }

        if (didStartFreeTrial) {
            val freeTrialEvent = InternalSuperwallEvent.FreeTrialStart(paywallInfo, product)
            Superwall.instance.track(freeTrialEvent)

        // SW-2214
        // https://linear.app/superwall/issue/SW-2214/%5Bandroid%5D-%5Bv2%5D-add-back-local-notifications
        // val notifications = paywallInfo.localNotifications.filter { it.type == NotificationType.TRIAL_STARTED }
        // NotificationScheduler.scheduleNotifications(notifications)
        } else {
            val subscriptionEvent =
                InternalSuperwallEvent.SubscriptionStart(paywallInfo, product)
            Superwall.instance.track(subscriptionEvent)
        }

        lastPaywallViewController = null
    }
}
