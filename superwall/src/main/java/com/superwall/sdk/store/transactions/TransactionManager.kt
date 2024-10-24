package com.superwall.sdk.store.transactions

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvents
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
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.SuperwallPaywallActivity
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.launch

class TransactionManager(
    private val storeKitManager: StoreKitManager,
    private val purchaseController: PurchaseController,
    private val eventsQueue: EventsQueue,
    private val activityProvider: ActivityProvider,
    private val factory: Factory,
    private val ioScope: IOScope,
    private val track: suspend (TrackableSuperwallEvent) -> Unit = {
        Superwall.instance.track(it)
    },
    private val dismiss: suspend (paywallView: PaywallView, result: PaywallResult) -> Unit,
    private val subscriptionStatus: () -> SubscriptionStatus = {
        Superwall.instance.subscriptionStatus.value
    },
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

    interface Factory :
        OptionsFactory,
        TriggerFactory,
        TransactionVerifierFactory,
        StoreTransactionFactory,
        DeviceHelperFactory

    private var lastPaywallView: PaywallView? = null

    suspend fun purchase(purchaseSource: PurchaseSource): PurchaseResult {
        val product =
            when (purchaseSource) {
                is PurchaseSource.Internal ->
                    storeKitManager.productsByFullId[purchaseSource.productId] ?: run {
                        log(
                            LogLevel.error,
                            "Trying to purchase (${purchaseSource.productId}) but the product has failed to load. Visit https://superwall.com/l/missing-products to diagnose.",
                        )
                        return PurchaseResult.Failed("Product not found")
                    }

                is PurchaseSource.External -> {
                    purchaseSource.product
                }
            }

        val rawStoreProduct = product.rawStoreProduct
        log(
            message =
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
                didPurchase(product, purchaseSource, isEligibleForTrial && product.hasFreeTrial)
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
        track(trackedEvent)

        val superwallOptions = factory.makeSuperwallOptions()
        if (superwallOptions.paywalls.automaticallyDismiss && purchaseSource is PurchaseSource.Internal) {
            dismiss(
                purchaseSource.paywallView,
                PaywallResult.Restored(),
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
                log(
                    message =

                        "Transaction Error: $errorMessage",
                    info =
                        mapOf(
                            "product_id" to product.fullIdentifier,
                            "paywall_vc" to purchaseSource.paywallView,
                        ),
                )

                ioScope.launchWithTracking {
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

                    track(trackedEvent)
                }
            }

            is PurchaseSource.External -> {
                log(
                    message = "Transaction Error: $errorMessage",
                    info = mapOf("product_id" to product.fullIdentifier),
                    error = Error(errorMessage),
                )
                ioScope.launch {
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

                    track(trackedEvent)
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
                ioScope.launch {
                    log(
                        message =

                            "Transaction Purchasing",
                        info = mapOf("paywall_vc" to source),
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
                track(trackedEvent)

                source.paywallView.loadingState = PaywallLoadingState.LoadingPurchase()

                lastPaywallView = source.paywallView
            }

            is PurchaseSource.External -> {
                ioScope.launch {
                    log(
                        message =

                            "External Transaction Purchasing",
                    )
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Start(product),
                        PaywallInfo.empty(),
                        product,
                        null,
                    )
                track(trackedEvent)
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
                ioScope.launch {
                    log(
                        message = "Transaction Succeeded",
                        info =
                            mapOf(
                                "product_id" to product.fullIdentifier,
                                "paywall_vc" to purchaseSource.paywallView,
                            ),
                    )
                }

                val transactionVerifier = factory.makeTransactionVerifier()
                val transaction =
                    transactionVerifier.getLatestTransaction(
                        factory = factory,
                    )

                storeKitManager.loadPurchasedProducts()

                trackTransactionDidSucceed(transaction, product, purchaseSource, didStartFreeTrial)

                if (factory.makeSuperwallOptions().paywalls.automaticallyDismiss) {
                    dismiss(
                        purchaseSource.paywallView,
                        PaywallResult.Purchased(product.fullIdentifier),
                    )
                }
            }

            is PurchaseSource.External -> {
                log(
                    message = "Transaction Succeeded",
                    info = mapOf("product_id" to product.fullIdentifier),
                )
                val transactionVerifier = factory.makeTransactionVerifier()
                val transaction =
                    transactionVerifier.getLatestTransaction(
                        factory = factory,
                    )

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
                ioScope.launch {
                    log(
                        message = "Transaction Abandoned",
                        info =
                            mapOf(
                                "product_id" to product.fullIdentifier,
                                "paywall_vc" to purchaseSource.paywallView,
                            ),
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
                track(trackedEvent)

                purchaseSource.paywallView.loadingState = PaywallLoadingState.Ready()
            }

            is PurchaseSource.External -> {
                ioScope.launch {
                    log(
                        message = "Transaction Abandoned",
                        info = mapOf("product_id" to product.fullIdentifier),
                    )
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Abandon(product),
                        PaywallInfo.empty(),
                        product,
                        null,
                    )
                track(trackedEvent)
            }
        }
    }

    private suspend fun handlePendingTransaction(purchaseSource: PurchaseSource) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                ioScope.launch {
                    log(
                        message = "Transaction Pending",
                        info = mapOf("paywall_vc" to purchaseSource.paywallView),
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
                track(trackedEvent)

                purchaseSource.paywallView.showAlert(
                    "Waiting for Approval",
                    "Thank you! This purchase is pending approval from your parent. Please try again once it is approved.",
                )
            }

            is PurchaseSource.External -> {
                ioScope.launch {
                    log(message = "Transaction Pending")
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Fail(TransactionError.Pending("Needs parental approval")),
                        PaywallInfo.empty(),
                        null,
                        null,
                    )
                track(trackedEvent)
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
        log(message = "Attempting Restore")

        val paywallInfo = paywallView?.info ?: PaywallInfo.empty()

        paywallView?.loadingState = PaywallLoadingState.LoadingPurchase()

        track(
            InternalSuperwallEvent.Restore(
                state = InternalSuperwallEvent.Restore.State.Start,
                paywallInfo = paywallInfo,
            ),
        )
        val restorationResult = purchaseController.restorePurchases()

        val hasRestored = restorationResult is RestorationResult.Restored
        val isUserSubscribed =
            subscriptionStatus() == SubscriptionStatus.ACTIVE

        if (hasRestored && isUserSubscribed) {
            log(message = "Transactions Restored")
            track(
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

            log(message = msg)
            track(
                InternalSuperwallEvent.Restore(
                    state = InternalSuperwallEvent.Restore.State.Failure(msg),
                    paywallInfo = paywallView?.info ?: PaywallInfo.empty(),
                ),
            )

            paywallView?.showAlert(
                title =
                    factory
                        .makeSuperwallOptions()
                        .paywalls.restoreFailed.title,
                message =
                    factory
                        .makeSuperwallOptions()
                        .paywalls.restoreFailed.message,
                closeActionTitle =
                    factory
                        .makeSuperwallOptions()
                        .paywalls.restoreFailed.closeButtonTitle,
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
                ioScope.launch {
                    log(
                        message = "Transaction Error",
                        info =
                            mapOf(
                                "product_id" to product.fullIdentifier,
                                "paywall_vc" to source.paywallView,
                            ),
                        error = error,
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
                track(trackedEvent)

                source.paywallView.showAlert(
                    "An error occurred",
                    error.message ?: "Unknown error",
                )
            }

            is PurchaseSource.External -> {
                ioScope.launch {
                    log(
                        message = "Transaction Error",
                        error = error,
                    )
                }

                val trackedEvent =
                    InternalSuperwallEvent.Transaction(
                        InternalSuperwallEvent.Transaction.State.Fail(
                            TransactionError.Failure(
                                error.message ?: "",
                                product,
                            ),
                        ),
                        PaywallInfo.empty(),
                        product,
                        null,
                    )
                track(trackedEvent)
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
                track(trackedEvent)
                eventsQueue.flushInternal()

                if (product.subscriptionPeriod == null) {
                    val nonRecurringEvent =
                        InternalSuperwallEvent.NonRecurringProductPurchase(
                            paywallInfo,
                            product,
                        )
                    track(nonRecurringEvent)
                } else {
                    if (didStartFreeTrial) {
                        val freeTrialEvent =
                            InternalSuperwallEvent.FreeTrialStart(paywallInfo, product)
                        track(freeTrialEvent)

                        val notifications =
                            paywallInfo.localNotifications.filter { it.type == LocalNotificationType.TrialStarted }
                        val paywallActivity =
                            paywallView.encapsulatingActivity?.get() as? SuperwallPaywallActivity
                                ?: return
                        paywallActivity.attemptToScheduleNotifications(
                            notifications = notifications,
                            factory = factory,
                        )
                    } else {
                        val subscriptionEvent =
                            InternalSuperwallEvent.SubscriptionStart(paywallInfo, product)
                        track(subscriptionEvent)
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
                track(trackedEvent)
                eventsQueue.flushInternal()

                if (product.subscriptionPeriod == null) {
                    val nonRecurringEvent =
                        InternalSuperwallEvent.NonRecurringProductPurchase(
                            PaywallInfo.empty(),
                            product,
                        )
                    track(nonRecurringEvent)
                } else {
                    if (didStartFreeTrial) {
                        val freeTrialEvent =
                            InternalSuperwallEvent.FreeTrialStart(PaywallInfo.empty(), product)
                        track(freeTrialEvent)
                    } else {
                        val subscriptionEvent =
                            InternalSuperwallEvent.SubscriptionStart(
                                PaywallInfo.empty(),
                                product,
                            )
                        track(subscriptionEvent)
                    }
                }
            }
        }
    }

    private fun log(
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
}
