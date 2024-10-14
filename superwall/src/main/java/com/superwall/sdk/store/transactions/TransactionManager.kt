package com.superwall.sdk.store.transactions

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvents
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.dependencies.SuperwallScopeFactory
import com.superwall.sdk.dependencies.TransactionVerifierFactory
import com.superwall.sdk.dependencies.TriggerFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
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
    sealed class PurchaseSource {
        data class Internal(
            val productId: String,
            val paywallView: PaywallView,
        ) : PurchaseSource()

        data class External(
            val product: StoreProduct,
        ) : PurchaseSource()
    }

    val scope = CoroutineScope(Dispatchers.IO)

    interface Factory :
        OptionsFactory,
        TriggerFactory,
        TransactionVerifierFactory,
        StoreTransactionFactory,
        DeviceHelperFactory,
        SuperwallScopeFactory

    private var lastPaywallView: PaywallView? = null

    suspend fun purchase(purchaseSource: PurchaseSource): PurchaseResult {
        val product =
            when (purchaseSource) {
                is PurchaseSource.Internal ->
                    storeKitManager.productsByFullId[purchaseSource.productId] ?: run {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.paywallTransactions,
                            message =
                            "Trying to purchase (${purchaseSource.productId}) but the product has failed to load. Visit https://superwall.com/l/missing-products to diagnose.",
                        )
                        return PurchaseResult.Failed("Product not found")
                    }

                is PurchaseSource.External -> {
                    purchaseSource.product
                }
            }

        val rawStoreProduct = product.rawStoreProduct
        Logger.debug(
            LogLevel.debug,
            LogScope.paywallTransactions,
            "!!! Purchasing product ${rawStoreProduct.hasFreeTrial}",
        )
        val productDetails = rawStoreProduct.underlyingProductDetails
        val activity =
            activityProvider.getCurrentActivity()
                ?: return PurchaseResult.Failed("Activity not found - required for starting the billing flow")

        prepareToPurchase(product, purchaseSource)

        val result =
            storeKitManager.purchaseController.purchase(
                activity = activity,
                productDetails = productDetails,
                offerId = rawStoreProduct.offerId,
                basePlanId = rawStoreProduct.basePlanId,
            )

        val isEligibleForTrial = rawStoreProduct.selectedOffer != null

        when (result) {
            is PurchaseResult.Purchased -> {
                didPurchase(product, purchaseSource, isEligibleForTrial)
            }

            is PurchaseResult.Restored -> {
                didRestore(
                    product = product,
                    purchaseSource = purchaseSource,
                )
            }

            is PurchaseResult.Failed -> {
                val superwallOptions = factory.makeSuperwallOptions()
                val shouldShowPurchaseFailureAlert =
                    superwallOptions.paywalls.shouldShowPurchaseFailureAlert
                val triggers = factory.makeTriggers()
                val transactionFailExists =
                    triggers.contains(SuperwallEvents.TransactionFail.rawName)

                if (shouldShowPurchaseFailureAlert && !transactionFailExists) {
                    trackFailure(
                        result.errorMessage,
                        product,
                        purchaseSource,
                    )
                    presentAlert(
                        Error(result.errorMessage),
                        product,
                        purchaseSource,
                    )
                } else {
                    trackFailure(
                        result.errorMessage,
                        product,
                        purchaseSource,
                    )
                    if (purchaseSource is PurchaseSource.Internal) {
                        purchaseSource.paywallView.togglePaywallSpinner(isHidden = true)
                    }
                }
            }

            is PurchaseResult.Pending -> {
                handlePendingTransaction(purchaseSource)
            }

            is PurchaseResult.Cancelled -> {
                trackCancelled(product, purchaseSource)
            }
        }
        return result
    }

    private suspend fun didRestore(
        product: StoreProduct? = null,
        purchaseSource: PurchaseSource,
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

        val trackedEvent =
            InternalSuperwallEvent.Transaction(
                state = InternalSuperwallEvent.Transaction.State.Restore(restoreType),
                paywallInfo = if (purchaseSource is PurchaseSource.Internal) purchaseSource.paywallView.info else PaywallInfo.empty(),
                product = product,
                model = null,
            )
        Superwall.instance.track(trackedEvent)

        val superwallOptions = factory.makeSuperwallOptions()
        if (superwallOptions.paywalls.automaticallyDismiss && purchaseSource is PurchaseSource.Internal) {
            Superwall.instance.dismiss(
                purchaseSource.paywallView,
                result = PaywallResult.Restored(),
            )
        }
    }

    private fun trackFailure(
        errorMessage: String,
        product: StoreProduct,
        purchaseSource: PurchaseSource,
    ) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                // Log the error
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallTransactions,
                    message = "Transaction Error: $errorMessage",
                    info =
                    mapOf(
                        "product_id" to product.fullIdentifier,
                        "paywall_vc" to purchaseSource.paywallView,
                    ),
                )

                factory.ioScope().launchWithTracking {
                    val paywallInfo = purchaseSource.paywallView.info
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

                    Superwall.instance.track(trackedEvent)

                }
            }

            is PurchaseSource.External -> {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallTransactions,
                    message = "Transaction Error: $errorMessage",
                    info =
                    mapOf(
                        "product_id" to product.fullIdentifier,
                    ),
                )
                factory.ioScope().launch {
                    val trackedEvent =
                        InternalSuperwallEvent.Transaction(
                            state =
                            InternalSuperwallEvent.Transaction.State.Fail(
                                TransactionError.Failure(
                                    errorMessage,
                                    product,
                                ),
                            ),
                            paywallInfo = PaywallInfo.empty(),
                            product = product,
                            model = null,
                        )

                    Superwall.instance.track(trackedEvent)
                }
            }
        }
    }

    private suspend fun prepareToPurchase(
        product: StoreProduct,
        source: PurchaseSource,
    ) {
        when (source) {
            is PurchaseSource.Internal -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Purchasing",
                        mapOf("paywall_vc" to source),
                        null,
                    )
                }

                val paywallInfo = source.paywallView.info
                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Start(product),
                        paywallInfo,
                        product,
                        null,
                    )
                Superwall.instance.track(trackedEvent)

                withContext(Dispatchers.Main) {
                    source.paywallView.loadingState = PaywallLoadingState.LoadingPurchase()
                }

                lastPaywallView = source.paywallView
            }

            is PurchaseSource.External -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "External Transaction Purchasing",
                        null,
                    )
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Start(product),
                        PaywallInfo.empty(),
                        product,
                        null,
                    )
                Superwall.instance.track(trackedEvent)
            }
        }
    }

    private suspend fun didPurchase(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        didStartFreeTrial: Boolean,
    ) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Succeeded",
                        mapOf(
                            "product_id" to product.fullIdentifier,
                            "paywall_vc" to purchaseSource.paywallView,
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

                trackTransactionDidSucceed(transaction, product, purchaseSource, didStartFreeTrial)

                if (Superwall.instance.options.paywalls.automaticallyDismiss) {
                    Superwall.instance.dismiss(
                        purchaseSource.paywallView,
                        PaywallResult.Purchased(product.fullIdentifier),
                    )
                }
            }

            is PurchaseSource.External -> {
                Logger.debug(
                    LogLevel.debug,
                    LogScope.paywallTransactions,
                    "Transaction Succeeded",
                    mapOf(
                        "product_id" to product.fullIdentifier,
                    ),
                    null,
                )
                val transactionVerifier = factory.makeTransactionVerifier()
                val transaction =
                    transactionVerifier.getLatestTransaction(
                        factory = factory,
                    )

                transaction?.let {
                    sessionEventsManager.enqueue(it)
                }

                storeKitManager.loadPurchasedProducts()

                trackTransactionDidSucceed(transaction, product, purchaseSource, didStartFreeTrial)
            }
        }
    }

    private suspend fun trackCancelled(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
    ) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Abandoned",
                        mapOf(
                            "product_id" to product.fullIdentifier,
                            "paywall_vc" to purchaseSource.paywallView,
                        ),
                        null,
                    )
                }

                val paywallInfo = purchaseSource.paywallView.info
                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Abandon(product),
                        paywallInfo,
                        product,
                        null,
                    )
                Superwall.instance.track(trackedEvent)

                withContext(Dispatchers.Main) {
                    purchaseSource.paywallView.loadingState = PaywallLoadingState.Ready()
                }
            }

            is PurchaseSource.External -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Abandoned",
                        mapOf(
                            "product_id" to product.fullIdentifier,
                        ),
                        null,
                    )
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Abandon(product),
                        PaywallInfo.empty(),
                        product,
                        null,
                    )
                Superwall.instance.track(trackedEvent)
            }
        }
    }

    private suspend fun handlePendingTransaction(purchaseSource: PurchaseSource) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Pending",
                        mapOf("paywall_vc" to purchaseSource.paywallView),
                        null,
                    )
                }

                val paywallInfo = purchaseSource.paywallView.info

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Pending("Needs parental approval")),
                        paywallInfo,
                        null,
                        null,
                    )
                Superwall.instance.track(trackedEvent)

                purchaseSource.paywallView.showAlert(
                    "Waiting for Approval",
                    "Thank you! This purchase is pending approval from your parent. Please try again once it is approved.",
                )
            }

            is PurchaseSource.External -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Pending",
                        null,
                    )
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Pending("Needs parental approval")),
                        PaywallInfo.empty(),
                        null,
                        null,
                    )
                Superwall.instance.track(trackedEvent)
            }
        }
    }

    /**
     * Attempt to restore purchases.
     *
     * @param paywallView The paywall view that initiated the restore or null if initiated externally.
     * @return A [RestorationResult] indicating the result of the restoration.
     */
    suspend fun tryToRestorePurchases(paywallView: PaywallView?): RestorationResult {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallTransactions,
            message = "Attempting Restore",
        )

        val paywallInfo = paywallView?.info ?: PaywallInfo.empty()

        paywallView?.loadingState = PaywallLoadingState.LoadingPurchase()

        Superwall.instance.track(
            InternalSuperwallEvent.Restore(
                state = InternalSuperwallEvent.Restore.State.Start,
                paywallInfo = paywallInfo,
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
                    paywallInfo = paywallView?.info ?: PaywallInfo.empty(),
                ),
            )
            if (paywallView != null) {
                didRestore(null, PurchaseSource.Internal("", paywallView))
            }
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
                    paywallInfo = paywallView?.info ?: PaywallInfo.empty(),
                ),
            )

            paywallView?.showAlert(
                title = Superwall.instance.options.paywalls.restoreFailed.title,
                message = Superwall.instance.options.paywalls.restoreFailed.message,
                closeActionTitle = Superwall.instance.options.paywalls.restoreFailed.closeButtonTitle,
            )
        }
        return restorationResult
    }

    private suspend fun presentAlert(
        error: Error,
        product: StoreProduct,
        source: PurchaseSource,
    ) {
        when (source) {
            is PurchaseSource.Internal -> {
                factory.ioScope().launch {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallTransactions,
                        "Transaction Error",
                        mapOf(
                            "product_id" to product.fullIdentifier,
                            "paywall_vc" to source.paywallView,
                        ),
                        error,
                    )
                }

                val paywallInfo = source.paywallView.info

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

                source.paywallView.showAlert(
                    "An error occurred",
                    error.message ?: "Unknown error",
                )
            }

            is PurchaseSource.External -> {
                // TODO: Implement displaying an alert here
            }
        }
    }

    private suspend fun trackTransactionDidSucceed(
        transaction: StoreTransaction?,
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        didStartFreeTrial: Boolean,
    ) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                val paywallView = lastPaywallView ?: return

                val paywallShowingFreeTrial = paywallView.paywall.isFreeTrialAvailable == true
                val didStartFreeTrial = product.hasFreeTrial && paywallShowingFreeTrial

                val paywallInfo = paywallView.info

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
                        val freeTrialEvent =
                            InternalSuperwallEvent.FreeTrialStart(paywallInfo, product)
                        Superwall.instance.track(freeTrialEvent)

                        val notifications =
                            paywallInfo.localNotifications.filter { it.type == LocalNotificationType.TrialStarted }
                        val paywallActivity =
                            paywallView.encapsulatingActivity?.get() as? SuperwallPaywallActivity
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

                lastPaywallView = null
            }

            is PurchaseSource.External -> {
                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Complete(product, transaction),
                        PaywallInfo.empty(),
                        product,
                        transaction,
                    )
                Superwall.instance.track(trackedEvent)
                eventsQueue.flushInternal()
                if (product.subscriptionPeriod == null) {
                    val nonRecurringEvent =
                        InternalSuperwallEvent.NonRecurringProductPurchase(
                            PaywallInfo.empty(),
                            product,
                        )
                    Superwall.instance.track(nonRecurringEvent)
                } else {
                    if (didStartFreeTrial) {
                        val freeTrialEvent =
                            InternalSuperwallEvent.FreeTrialStart(PaywallInfo.empty(), product)
                        Superwall.instance.track(freeTrialEvent)
                    } else {
                        val subscriptionEvent =
                            InternalSuperwallEvent.SubscriptionStart(
                                PaywallInfo.empty(),
                                product,
                            )
                        Superwall.instance.track(subscriptionEvent)
                    }
                }
            }
        }
    }
}
