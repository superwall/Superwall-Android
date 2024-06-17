package com.superwall.sdk.store.transactions

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventObjc
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.dependencies.TransactionVerifierFactory
import com.superwall.sdk.dependencies.TriggerFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.SuperwallPaywallActivity
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransactionManager(
    private val storeKitManager: StoreKitManager,
    private val purchaseController: PurchaseController,
    private val sessionEventsManager: SessionEventsManager,
    private val eventsQueue: EventsQueue,
    private val activityProvider: ActivityProvider,
    private val factory: Factory,
    private val context: Context,
) {
    val scope = CoroutineScope(Dispatchers.IO)

    interface Factory :
        OptionsFactory,
        TriggerFactory,
        TransactionVerifierFactory,
        StoreTransactionFactory,
        DeviceHelperFactory

    private var lastPaywallViewController: PaywallViewController? = null

    suspend fun purchase(
        productId: String,
        paywallViewController: PaywallViewController,
    ) {
        val product =
            storeKitManager.productsByFullId[productId] ?: run {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallTransactions,
                    message =
                        "Trying to purchase ($productId) but the product has failed to load. Visit https://superwall.com/l/missing-products to diagnose.",
                )
                return
            }

        val rawStoreProduct = product.rawStoreProduct
        println("!!! Purchasing product ${rawStoreProduct.hasFreeTrial}")
        val productDetails = rawStoreProduct.underlyingProductDetails
        val activity = activityProvider.getCurrentActivity() ?: return

        prepareToStartTransaction(product, paywallViewController)

        val result =
            storeKitManager.purchaseController.purchase(
                activity = activity,
                productDetails = productDetails,
                offerId = rawStoreProduct.offerId,
                basePlanId = rawStoreProduct.basePlanId,
            )

        when (result) {
            is PurchaseResult.Purchased -> {
                didPurchase(product, paywallViewController)
            }

            is PurchaseResult.Restored -> {
                didRestore(
                    product = product,
                    paywallViewController = paywallViewController,
                )
            }

            is PurchaseResult.Failed -> {
                val superwallOptions = factory.makeSuperwallOptions()
                val shouldShowPurchaseFailureAlert =
                    superwallOptions.paywalls.shouldShowPurchaseFailureAlert
                val triggers = factory.makeTriggers()
                val transactionFailExists =
                    triggers.contains(SuperwallEventObjc.TransactionFail.rawName)

                if (shouldShowPurchaseFailureAlert && !transactionFailExists) {
                    trackFailure(
                        result.errorMessage,
                        product,
                        paywallViewController,
                    )
                    presentAlert(
                        Error(result.errorMessage),
                        product,
                        paywallViewController,
                    )
                } else {
                    trackFailure(
                        result.errorMessage,
                        product,
                        paywallViewController,
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

    private suspend fun didRestore(
        product: StoreProduct? = null,
        paywallViewController: PaywallViewController,
    ) {
        val purchasingCoordinator = factory.makeTransactionVerifier()
        var transaction: StoreTransaction?
        val restoreType: RestoreType

        if (product != null) {
            // Product exists so much have been via a purchase of a specific product.
            transaction =
                purchasingCoordinator.getLatestTransaction(
                    factory = factory,
                )
            restoreType = RestoreType.ViaPurchase(transaction)
        } else {
            // Otherwise it was a generic restore.
            restoreType = RestoreType.ViaRestore
        }

        val paywallInfo = paywallViewController.info

        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                state = InternalSuperwallEvent.Transaction.State.Restore(restoreType),
                paywallInfo = paywallInfo,
                product = product,
                model = null,
            )
        Superwall.instance.track(trackedEvent)

        val superwallOptions = factory.makeSuperwallOptions()
        if (superwallOptions.paywalls.automaticallyDismiss) {
            Superwall.instance.dismiss(paywallViewController, result = PaywallResult.Restored())
        }
    }

    private fun trackFailure(
        errorMessage: String,
        product: StoreProduct,
        paywallViewController: PaywallViewController,
    ) {
        // Log the error
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallTransactions,
            message = "Transaction Error: $errorMessage",
            info =
                mapOf(
                    "product_id" to product.fullIdentifier,
                    "paywall_vc" to paywallViewController,
                ),
        )

        // Launch a coroutine to handle async tasks
        scope.launch {
            val paywallInfo = paywallViewController.info
            val trackedEvent =
                InternalSuperwallEvent.Transaction(
                    state =
                        InternalSuperwallEvent.Transaction.State.Fail(
                            TransactionError.Failure(
                                errorMessage,
                                product,
                            ),
                        ),
                    paywallInfo = paywallInfo,
                    product = product,
                    model = null,
                )

            // Assuming Superwall.instance.track and sessionEventsManager.triggerSession.trackTransactionError are suspend functions
            Superwall.instance.track(trackedEvent)
        }
    }

    private suspend fun prepareToStartTransaction(
        product: StoreProduct,
        paywallViewController: PaywallViewController,
    ) {
        scope.launch {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Purchasing",
                mapOf("paywall_vc" to paywallViewController),
                null,
            )
        }

        val paywallInfo = paywallViewController.info
        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                InternalSuperwallEvent.Transaction.State.Start(product),
                paywallInfo,
                product,
                null,
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
        paywallViewController: PaywallViewController,
    ) {
        scope.launch {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Succeeded",
                mapOf(
                    "product_id" to product.fullIdentifier,
                    "paywall_vc" to paywallViewController,
                ),
                null,
            )
        }

        val transactionVerifier = factory.makeTransactionVerifier()
        val transaction =
            transactionVerifier.getLatestTransaction(
                factory = factory,
            )

        transaction?.let {
            sessionEventsManager.enqueue(it)
        }

        storeKitManager.loadPurchasedProducts()

        trackTransactionDidSucceed(transaction, product)

        if (Superwall.instance.options.paywalls.automaticallyDismiss) {
            Superwall.instance.dismiss(
                paywallViewController,
                PaywallResult.Purchased(product.fullIdentifier),
            )
        }
    }

    private suspend fun trackCancelled(
        product: StoreProduct,
        paywallViewController: PaywallViewController,
    ) {
        scope.launch {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Abandoned",
                mapOf(
                    "product_id" to product.fullIdentifier,
                    "paywall_vc" to paywallViewController,
                ),
                null,
            )
        }

        val paywallInfo = paywallViewController.info
        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                InternalSuperwallEvent.Transaction.State.Abandon(product),
                paywallInfo,
                product,
                null,
            )
        Superwall.instance.track(trackedEvent)

        withContext(Dispatchers.Main) {
            paywallViewController.loadingState = PaywallLoadingState.Ready()
        }
    }

    private suspend fun handlePendingTransaction(paywallViewController: PaywallViewController) {
        scope.launch {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Pending",
                mapOf("paywall_vc" to paywallViewController),
                null,
            )
        }

        val paywallInfo = paywallViewController.info

        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Pending("Needs parental approval")),
                paywallInfo,
                null,
                null,
            )
        Superwall.instance.track(trackedEvent)

        paywallViewController.presentAlert(
            "Waiting for Approval",
            "Thank you! This purchase is pending approval from your parent. Please try again once it is approved.",
        )
    }

    suspend fun tryToRestore(paywallViewController: PaywallViewController) {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallTransactions,
            message = "Attempting Restore",
        )

        paywallViewController.loadingState = PaywallLoadingState.LoadingPurchase()

        Superwall.instance.track(
            InternalSuperwallEvent.Restore(
                state = InternalSuperwallEvent.Restore.State.Start,
                paywallInfo = paywallViewController.info,
            ),
        )
        val restorationResult = purchaseController.restorePurchases()

        val hasRestored = restorationResult is RestorationResult.Restored
        val isUserSubscribed =
            Superwall.instance.subscriptionStatus.value == SubscriptionStatus.ACTIVE

        if (hasRestored && isUserSubscribed) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallTransactions,
                message = "Transactions Restored",
            )
            Superwall.instance.track(
                InternalSuperwallEvent.Restore(
                    state = InternalSuperwallEvent.Restore.State.Complete,
                    paywallInfo = paywallViewController.info,
                ),
            )
            didRestore(paywallViewController = paywallViewController)
        } else {
            val msg = "Transactions Failed to Restore.${
                if (hasRestored && !isUserSubscribed) {
                    " The user's subscription status is \"inactive\", but the restoration result is \"restored\"." +
                        " Ensure the subscription status is active before confirming successful restoration."
                } else {
                    " Original restoration error message: ${
                        when (restorationResult) {
                            is RestorationResult.Failed -> restorationResult.error?.localizedMessage
                            else -> null
                        }
                    }"
                }
            }"

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallTransactions,
                message = msg,
            )
            Superwall.instance.track(
                InternalSuperwallEvent.Restore(
                    state = InternalSuperwallEvent.Restore.State.Failure(msg),
                    paywallInfo = paywallViewController.info,
                ),
            )

            paywallViewController.presentAlert(
                title = Superwall.instance.options.paywalls.restoreFailed.title,
                message = Superwall.instance.options.paywalls.restoreFailed.message,
                closeActionTitle = Superwall.instance.options.paywalls.restoreFailed.closeButtonTitle,
            )
        }
    }

    private suspend fun presentAlert(
        error: Error,
        product: StoreProduct,
        paywallViewController: PaywallViewController,
    ) {
        scope.launch {
            Logger.debug(
                LogLevel.debug,
                LogScope.paywallTransactions,
                "Transaction Error",
                mapOf(
                    "product_id" to product.fullIdentifier,
                    "paywall_vc" to paywallViewController,
                ),
                error,
            )
        }

        val paywallInfo = paywallViewController.info

        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                InternalSuperwallEvent.Transaction.State.Fail(
                    TransactionError.Failure(
                        error.message ?: "",
                        product,
                    ),
                ),
                paywallInfo,
                product,
                null,
            )
        Superwall.instance.track(trackedEvent)

        paywallViewController.presentAlert(
            "An error occurred",
            error.message ?: "Unknown error",
        )
    }

    // ... and so on for the other methods ...
    private suspend fun trackTransactionDidSucceed(
        transaction: StoreTransaction?,
        product: StoreProduct,
    ) {
        val paywallViewController = lastPaywallViewController ?: return

        val paywallShowingFreeTrial = paywallViewController.paywall.isFreeTrialAvailable == true
        val didStartFreeTrial = product.hasFreeTrial && paywallShowingFreeTrial

        val paywallInfo = paywallViewController.info

        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                InternalSuperwallEvent.Transaction.State.Complete(product, transaction),
                paywallInfo,
                product,
                transaction,
            )
        Superwall.instance.track(trackedEvent)

        // Immediately flush the events queue on transaction complete.
        eventsQueue.flushInternal()

        if (product.subscriptionPeriod == null) {
            val nonRecurringEvent =
                InternalSuperwallEvent.NonRecurringProductPurchase(
                    paywallInfo,
                    product,
                )
            Superwall.instance.track(nonRecurringEvent)
        } else {
            if (didStartFreeTrial) {
                val freeTrialEvent = InternalSuperwallEvent.FreeTrialStart(paywallInfo, product)
                Superwall.instance.track(freeTrialEvent)

                val notifications =
                    paywallInfo.localNotifications.filter { it.type == LocalNotificationType.TrialStarted }
                val paywallActivity =
                    paywallViewController.encapsulatingActivity as? SuperwallPaywallActivity
                        ?: return
                paywallActivity.attemptToScheduleNotifications(
                    notifications = notifications,
                    factory = factory,
                    context = context,
                )
            } else {
                val subscriptionEvent =
                    InternalSuperwallEvent.SubscriptionStart(paywallInfo, product)
                Superwall.instance.track(subscriptionEvent)
            }
        }

        lastPaywallViewController = null
    }
}
