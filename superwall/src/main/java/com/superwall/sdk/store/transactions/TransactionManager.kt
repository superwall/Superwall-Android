package com.superwall.sdk.store.transactions

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvents
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.AttributesFactory
import com.superwall.sdk.dependencies.CacheFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.EnrichmentFactory
import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.dependencies.HasInternalPurchaseControllerFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.dependencies.TriggerFactory
import com.superwall.sdk.dependencies.WebToAppFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.AlertControllerFactory.AlertProps
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.request.PaywallLogic
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.PurchasingProductdIds
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.ProductReplacement
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeTransactionHandler
import com.superwall.sdk.web.openRestoreOnWeb
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TransactionManager internal constructor(
    internal val dependencies: TransactionDependencies,
) {
    // Short names for the collaborators while the flows move onto their actors.
    private val storeManager = dependencies.storeManager
    private val purchaseController = dependencies.purchaseController
    private val eventsQueue = dependencies.eventsQueue
    private val storage = dependencies.storage
    private val activityProvider = dependencies.activityProvider
    private val factory = dependencies.factory
    private val ioScope = dependencies.ioScope
    private val track = dependencies.track
    private val dismiss = dependencies.dismiss
    private val showAlert = dependencies.showAlert
    private val subscriptionStatus = dependencies.subscriptionStatus
    private val entitlementsById = dependencies.entitlementsById
    private val allEntitlementsByProductId = dependencies.allEntitlementsByProductId
    private val webEntitlements = dependencies.webEntitlements
    private val currentCustomerInfo = dependencies.currentCustomerInfo
    private val showRestoreDialogForWeb = dependencies.showRestoreDialogForWeb
    private val notifyBackendOfReceipts = dependencies.notifyBackendOfReceipts
    private val refreshReceipt = dependencies.refreshReceipt
    private val updateState = dependencies.updateState
    private val notifyOfTransactionComplete = dependencies.notifyOfTransactionComplete
    private val notifyOfTransactionAbandon = dependencies.notifyOfTransactionAbandon
    private val testMode = dependencies.testMode
    private val testModeTransactionHandler = dependencies.testModeTransactionHandler
    private val setSubscriptionStatus = dependencies.setSubscriptionStatus
    private val activeDeviceEntitlements = dependencies.activeDeviceEntitlements
    private val openRestoreOnWeb = dependencies.openRestoreOnWeb

    /**
     * Kept for binary compatibility. The SDK builds [TransactionManager] with the internal
     * constructor; this one reads the device entitlements and web restore from the configured SDK.
     */
    @Deprecated("Internal SDK class; construct it through Superwall.configure.")
    constructor(
        storeManager: StoreManager,
        purchaseController: PurchaseController,
        eventsQueue: EventsQueue,
        storage: Storage,
        activityProvider: ActivityProvider,
        factory: Factory,
        ioScope: IOScope,
        track: suspend (TrackableSuperwallEvent) -> Unit = {
            SuperwallGraph.track(it)
        },
        dismiss: suspend (paywallId: String, result: PaywallResult) -> Unit,
        showAlert: (AlertProps) -> Unit,
        subscriptionStatus: () -> SubscriptionStatus = {
            SuperwallGraph.container?.entitlements?.status?.value ?: SubscriptionStatus.Unknown
        },
        entitlementsById: (String) -> Set<Entitlement>,
        allEntitlementsByProductId: () -> Map<String, Set<Entitlement>>,
        webEntitlements: () -> Set<Entitlement> = {
            SuperwallGraph.container?.entitlements?.web ?: emptySet()
        },
        currentCustomerInfo: () -> CustomerInfo = {
            SuperwallGraph.container?.customerInfoManager?.customerInfo?.value ?: CustomerInfo.empty()
        },
        showRestoreDialogForWeb: suspend () -> Unit,
        notifyBackendOfReceipts: suspend () -> Unit = {},
        refreshReceipt: () -> Unit,
        updateState: (cacheKey: String, update: PaywallViewState.Updates) -> Unit,
        notifyOfTransactionComplete: suspend (
            paywallCacheKey: String,
            trialEndDate: Long?,
            productId: String,
            didStartFreeTrial: Boolean,
        ) -> Unit,
        notifyOfTransactionAbandon: suspend (paywallCacheKey: String) -> Unit = {},
        testMode: TestMode? = null,
        testModeTransactionHandler: TestModeTransactionHandler? = null,
        setSubscriptionStatus: ((SubscriptionStatus) -> Unit)? = null,
    ) : this(
        TransactionDependencies(
            storeManager = storeManager,
            purchaseController = purchaseController,
            eventsQueue = eventsQueue,
            storage = storage,
            activityProvider = activityProvider,
            factory = factory,
            ioScope = ioScope,
            track = track,
            dismiss = dismiss,
            showAlert = showAlert,
            subscriptionStatus = subscriptionStatus,
            entitlementsById = entitlementsById,
            allEntitlementsByProductId = allEntitlementsByProductId,
            webEntitlements = webEntitlements,
            currentCustomerInfo = currentCustomerInfo,
            showRestoreDialogForWeb = showRestoreDialogForWeb,
            notifyBackendOfReceipts = notifyBackendOfReceipts,
            refreshReceipt = refreshReceipt,
            updateState = updateState,
            notifyOfTransactionComplete = notifyOfTransactionComplete,
            notifyOfTransactionAbandon = notifyOfTransactionAbandon,
            testMode = testMode,
            testModeTransactionHandler = testModeTransactionHandler,
            setSubscriptionStatus = setSubscriptionStatus,
            activeDeviceEntitlements = { SuperwallGraph.container?.entitlements?.activeDeviceEntitlements ?: emptySet() },
            openRestoreOnWeb = { SuperwallGraph.container?.openRestoreOnWeb() },
        ),
    )

    sealed class PurchaseSource {
        data class Internal(
            val productId: String,
            val state: PaywallViewState,
            // Set when the paywall asks to replace the user's existing subscription
            val replacementMode: ProductReplacement.Mode? = null,
        ) : PurchaseSource() {
            val paywallInfo: PaywallInfo
                get() = state.info
        }

        data class ExternalPurchase(
            val product: StoreProduct,
        ) : PurchaseSource()

        data class ObserverMode(
            val product: StoreProduct,
        ) : PurchaseSource()
    }

    // TransactionVerifierFactory is deprecated for SDK consumers; the SDK still implements it.
    @Suppress("DEPRECATION")
    interface Factory :
        OptionsFactory,
        TriggerFactory,
        com.superwall.sdk.dependencies.TransactionVerifierFactory,
        StoreTransactionFactory,
        DeviceHelperFactory,
        CacheFactory,
        HasExternalPurchaseControllerFactory,
        HasInternalPurchaseControllerFactory,
        WebToAppFactory,
        AttributesFactory,
        EnrichmentFactory

    private var transactionsInProgress: ConcurrentHashMap<String, ProductDetails> =
        ConcurrentHashMap()

    private val shouldObserveTransactionFinishingAutomatically: Boolean
        get() = factory.makeSuperwallOptions().shouldObservePurchases

    init {
        if (shouldObserveTransactionFinishingAutomatically) {
            ioScope.launch {
                storeManager.billing.purchaseResults
                    .asSharedFlow()
                    .dropWhile {
                        transactionsInProgress.isEmpty()
                    }.filterNotNull()
                    .collectLatest { it: InternalPurchaseResult ->
                        val state =
                            when (it) {
                                is InternalPurchaseResult.Purchased -> {
                                    it.purchase.products.forEach {
                                        transactionsInProgress.remove(it)
                                    }
                                    PurchasingObserverState.PurchaseResult(
                                        BillingResult
                                            .newBuilder()
                                            .setResponseCode(BillingClient.BillingResponseCode.OK)
                                            .build(),
                                        listOf(it.purchase),
                                    )
                                }

                                is InternalPurchaseResult.Cancelled -> {
                                    val last = transactionsInProgress.entries.last()
                                    transactionsInProgress.remove(last.key)
                                    PurchasingObserverState.PurchaseResult(
                                        BillingResult
                                            .newBuilder()
                                            .setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED)
                                            .build(),
                                        emptyList(),
                                    )
                                }

                                else -> {
                                    val last = transactionsInProgress.entries.last()
                                    transactionsInProgress.remove(last.key)
                                    PurchasingObserverState.PurchaseError(
                                        error =
                                            (it as? InternalPurchaseResult.Failed)?.error
                                                ?: Throwable("Unknown error"),
                                        product = last.value,
                                    )
                                }
                            }
                        handle(it, state)
                    }
            }
        }
    }

    internal suspend fun handle(
        result: InternalPurchaseResult,
        state: PurchasingObserverState,
    ) {
        if (transactionsInProgress.isEmpty()) {
            Logger.debug(
                LogLevel.error,
                LogScope.paywallTransactions,
                "Transactions in progress is empty but a transaction was attempted to be handled." +
                    " This is likely a bug. Have you tracked the purchase start?",
            )
            return
        }

        val lastProduct = transactionsInProgress.entries.last()
        when (result) {
            is InternalPurchaseResult.Purchased -> {
                refreshReceipt()
                transactionsInProgress.remove(lastProduct.key)
                val state = state as PurchasingObserverState.PurchaseResult
                state.purchases?.forEach { purchase ->
                    purchase.products.map {
                        storeManager.getProductFromCache(it)?.let { product ->
                            didPurchase(
                                product,
                                PurchaseSource.ObserverMode(product),
                                product.hasFreeTrial,
                                purchase,
                                shouldDismiss = true, // gets ignored later on,
                            )
                        }
                    }
                }
            }

            is InternalPurchaseResult.Cancelled -> {
                val product = StoreProduct(RawStoreProduct.from(lastProduct.value))
                trackCancelled(
                    product = product,
                    purchaseSource = PurchaseSource.ObserverMode(product),
                )
                transactionsInProgress.remove(lastProduct.key)
            }

            is InternalPurchaseResult.Failed -> {
                transactionsInProgress.remove(lastProduct.key)
                val state = state as PurchasingObserverState.PurchaseError
                val product = StoreProduct(RawStoreProduct.from(state.product))
                trackFailure(
                    state.error.localizedMessage
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
                        }?.message ?: "Unknown error",
                    product,
                    PurchaseSource.ObserverMode(product),
                )
            }

            InternalPurchaseResult.Pending -> {
                val result = state as PurchasingObserverState.PurchaseResult
                transactionsInProgress.remove(lastProduct.key)
                result.purchases?.forEach { purchase ->
                    purchase.products.map {
                        storeManager.getProductFromCache(it)?.let { product ->
                            handlePendingTransaction(PurchaseSource.ObserverMode(product))
                        }
                    }
                }
            }

            InternalPurchaseResult.Restored -> {
                val state = state as PurchasingObserverState.PurchaseResult
                transactionsInProgress.remove(lastProduct.key)
                state.purchases?.forEach { purchase ->
                    purchase.products.map {
                        storeManager.getProductFromCache(it)?.let { product ->
                            didRestore(product, PurchaseSource.ObserverMode(product))
                        }
                    }
                }
            }
        }
    }

    fun updatePaymentQueue(removedTransactions: List<String>) {
        var stored = storage.read(PurchasingProductdIds)
        val remainingTransactions =
            stored?.filter { transaction ->
                !removedTransactions.any { it == transaction }
            } ?: emptyList()
        storage.write(PurchasingProductdIds, remainingTransactions.toSet())
    }

    suspend fun purchase(
        purchaseSource: PurchaseSource,
        shouldDismiss: Boolean = true,
    ): PurchaseResult {
        val product =
            when (purchaseSource) {
                is PurchaseSource.Internal -> {
                    storeManager.getProductFromCache(purchaseSource.productId) ?: run {
                        log(
                            LogLevel.error,
                            "Trying to purchase (${purchaseSource.productId}) but the product has failed to load. Visit https://superwall.com/l/missing-products to diagnose.",
                        )
                        return PurchaseResult.Failed("Product not found")
                    }
                }

                is PurchaseSource.ExternalPurchase -> {
                    purchaseSource.product
                }

                is PurchaseSource.ObserverMode -> {
                    purchaseSource.product
                }
            }

        // Test mode intercept: simulate purchase without real billing
        if (testMode?.isTestMode == true && testModeTransactionHandler != null) {
            prepareToPurchase(product, purchaseSource)
            val result = testModeTransactionHandler.handlePurchase(product, purchaseSource)
            when (result) {
                is PurchaseResult.Purchased -> {
                    // In test mode, set subscription status directly (no real receipt to verify)
                    val status = testMode.buildSubscriptionStatus()
                    setSubscriptionStatus?.invoke(status)
                    trackTransactionDidSucceed(null, product, purchaseSource, product.hasFreeTrial)
                    if (shouldDismiss && purchaseSource is PurchaseSource.Internal) {
                        if (factory.makeSuperwallOptions().paywalls.automaticallyDismiss) {
                            dismiss(
                                purchaseSource.paywallInfo.cacheKey,
                                PaywallResult.Purchased(product.fullIdentifier),
                            )
                        }
                    }
                }

                is PurchaseResult.Failed -> {
                    trackFailure(result.errorMessage, product, purchaseSource)
                    if (purchaseSource is PurchaseSource.Internal) {
                        val superwallOptions = factory.makeSuperwallOptions()
                        val triggers = factory.makeTriggers()
                        val transactionFailExists =
                            triggers.contains(SuperwallEvents.TransactionFail.rawName)
                        if (superwallOptions.paywalls.shouldShowPurchaseFailureAlert && !transactionFailExists) {
                            presentAlert(
                                Error(result.errorMessage),
                                product,
                                purchaseSource.state,
                            )
                        } else {
                            updateState(
                                purchaseSource.paywallInfo.cacheKey,
                                PaywallViewState.Updates.ToggleSpinner(hidden = true),
                            )
                        }
                    }
                }

                is PurchaseResult.Cancelled -> {
                    trackCancelled(product, purchaseSource)
                }

                is PurchaseResult.Pending -> {
                    handlePendingTransaction(purchaseSource)
                }
            }
            return result
        }

        // Custom product flow: store == CUSTOM products are handled by the external
        // PurchaseController, not Google Play Billing.
        if (product.isCustomProduct) {
            return handleCustomProductPurchase(product, purchaseSource, shouldDismiss)
        }

        val rawStoreProduct =
            product.rawStoreProduct
                ?: return PurchaseResult.Failed("Missing raw store product for ${product.fullIdentifier}")
        log(
            message =
                "!!! Purchasing product ${rawStoreProduct.hasFreeTrial}",
        )
        val activity =
            activityProvider.getCurrentActivity()
                ?: return PurchaseResult.Failed("Activity not found - required for starting the billing flow")

        prepareToPurchase(product, purchaseSource)
        val result =
            resolveReplacement(product, purchaseSource).fold(
                onSuccess = { replacement ->
                    storeManager.purchaseController.purchase(
                        activity = activity,
                        product = product,
                        offerId = rawStoreProduct.offerId,
                        basePlanId = rawStoreProduct.basePlanId,
                        replacement = replacement,
                    )
                },
                onFailure = { replacementLookupFailed(product) },
            )

        if (purchaseSource is PurchaseSource.ExternalPurchase &&
            factory.makeHasExternalPurchaseController() &&
            !factory.makeHasInternalPurchaseController()
        ) {
            return result
        }

        val isEligibleForTrial = rawStoreProduct.selectedOffer != null

        when (result) {
            is PurchaseResult.Purchased -> {
                didPurchase(product, purchaseSource, isEligibleForTrial && product.hasFreeTrial, shouldDismiss = shouldDismiss)
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
                    if (purchaseSource is PurchaseSource.Internal) {
                        presentAlert(
                            Error(result.errorMessage),
                            product,
                            purchaseSource.state,
                        )
                    }
                } else {
                    trackFailure(
                        result.errorMessage,
                        product,
                        purchaseSource,
                    )
                    if (purchaseSource is PurchaseSource.Internal) {
                        updateState(
                            purchaseSource.paywallInfo.cacheKey,
                            PaywallViewState.Updates.ToggleSpinner(hidden = true),
                        )
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

    /**
     * Finds the subscription a purchase should replace when the paywall asked for a
     * replacement. Only an active subscription that shares an entitlement with [product]
     * is replaced; without one, the purchase is a normal purchase. Fails when Play can't
     * say which subscriptions the user has, since buying anyway could add a second one.
     */
    private suspend fun resolveReplacement(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
    ): Result<ProductReplacement?> {
        val mode =
            (purchaseSource as? PurchaseSource.Internal)?.replacementMode
                ?: return Result.success(null)
        // Only a subscription can replace a subscription
        if (product.subscriptionPeriod == null) {
            log(message = "Not replacing a subscription: ${product.fullIdentifier} isn't a subscription.")
            return Result.success(null)
        }
        val entitlementIds = entitlementsById(product.fullIdentifier).map { it.id }.toSet()
        if (entitlementIds.isEmpty()) {
            log(message = "Not replacing a subscription: ${product.fullIdentifier} has no entitlements.")
            return Result.success(null)
        }

        val replacement =
            if (product.isCustomProduct) {
                // Custom store subscriptions aren't visible to Play, so go by the active
                // entitlements the PurchaseController reported.
                val shared =
                    (subscriptionStatus() as? SubscriptionStatus.Active)
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
                    storeManager.billing.querySubscriptionPurchases().getOrElse {
                        log(
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

        log(
            message =
                replacement?.let { "Replacing ${it.oldProductId} with ${product.fullIdentifier} (${mode.rawName})." }
                    ?: "Not replacing a subscription: none shares an entitlement with ${product.fullIdentifier}.",
        )
        return Result.success(replacement)
    }

    private fun replacementLookupFailed(product: StoreProduct): PurchaseResult =
        PurchaseResult.Failed(
            "Couldn't check your current subscription, so ${product.productIdentifier} wasn't purchased. " +
                "Please try again.",
        )

    /**
     * Handles purchase of a custom (store == CUSTOM) product. Requires an external
     * PurchaseController; fails fast with a clear error otherwise. Pre-generates a
     * UUID transaction identifier on each attempt, routes the purchase through
     * [PurchaseController.purchase(activity, product, basePlanId, offerId)], and
     * constructs a StoreTransaction without touching Google Play Billing / receipts.
     */
    private suspend fun handleCustomProductPurchase(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        shouldDismiss: Boolean,
    ): PurchaseResult {
        if (!factory.makeHasExternalPurchaseController()) {
            val message =
                "Custom product \"${product.fullIdentifier}\" can only be purchased using a " +
                    "PurchaseController. Set one via Superwall.configure(..., purchaseController:) " +
                    "and handle products where isCustomProduct == true in " +
                    "purchase(activity, product, basePlanId, offerId)."
            log(message = message, error = Error(message))
            trackFailure(message, product, purchaseSource)
            if (purchaseSource is PurchaseSource.Internal) {
                // Surface the misconfiguration on the paywall, mirroring other purchase
                // failures, instead of silently hiding the spinner.
                val options = factory.makeSuperwallOptions()
                val transactionFailExists =
                    factory.makeTriggers().contains(SuperwallEvents.TransactionFail.rawName)
                if (options.paywalls.shouldShowPurchaseFailureAlert && !transactionFailExists) {
                    presentAlert(Error(message), product, purchaseSource.state)
                } else {
                    updateState(
                        purchaseSource.paywallInfo.cacheKey,
                        PaywallViewState.Updates.ToggleSpinner(hidden = true),
                    )
                }
            }
            return PurchaseResult.Failed(message)
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
                entitlements = entitlementsById(product.fullIdentifier),
                customerInfo = currentCustomerInfo(),
            )

        prepareToPurchase(product, purchaseSource)

        // prepareToPurchase skips the Start event for an external-only controller, but a custom
        // purchase is otherwise invisible to Superwall — emit Start so it pairs with the Complete
        // tracked on success and transaction funnels stay balanced.
        val isExternalOnly =
            purchaseSource is PurchaseSource.ExternalPurchase &&
                factory.makeHasExternalPurchaseController() &&
                !factory.makeHasInternalPurchaseController()
        if (isExternalOnly) {
            track(TransactionAnalytics.start(product, purchaseSource, dependencies.demand()))
        }

        val activity =
            activityProvider.getCurrentActivity()
                ?: return PurchaseResult.Failed("Activity not found - required for starting the purchase flow")
        val result =
            resolveReplacement(product, purchaseSource).fold(
                onSuccess = { replacement ->
                    storeManager.purchaseController.purchase(
                        activity = activity,
                        product = product,
                        basePlanId = null,
                        offerId = null,
                        replacement = replacement,
                    )
                },
                onFailure = { replacementLookupFailed(product) },
            )

        // For an external-only controller the dev's flow owns the rest of the lifecycle; still
        // build + track the transaction so analytics include the custom txn id.
        if (isExternalOnly) {
            if (result is PurchaseResult.Purchased) {
                val transaction =
                    factory.makeStoreTransaction(
                        customTransactionId = txnId,
                        productIdentifier = product.fullIdentifier,
                        purchaseDate = Date(),
                    )
                trackTransactionDidSucceed(transaction, product, purchaseSource, didStartFreeTrial)
            }
            return result
        }

        when (result) {
            is PurchaseResult.Purchased -> {
                val transaction =
                    factory.makeStoreTransaction(
                        customTransactionId = txnId,
                        productIdentifier = product.fullIdentifier,
                        purchaseDate = Date(),
                    )
                // Skip storeManager.loadPurchasedProducts — no Play receipt for custom products.
                trackTransactionDidSucceed(transaction, product, purchaseSource, didStartFreeTrial)
                if (shouldDismiss &&
                    purchaseSource is PurchaseSource.Internal &&
                    factory.makeSuperwallOptions().paywalls.automaticallyDismiss
                ) {
                    dismiss(
                        purchaseSource.paywallInfo.cacheKey,
                        PaywallResult.Purchased(product.fullIdentifier),
                    )
                } else if (purchaseSource is PurchaseSource.Internal) {
                    updateState(
                        purchaseSource.paywallInfo.cacheKey,
                        PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready),
                    )
                }
            }

            is PurchaseResult.Failed -> {
                trackFailure(result.errorMessage, product, purchaseSource)
                if (purchaseSource is PurchaseSource.Internal) {
                    val options = factory.makeSuperwallOptions()
                    val triggers = factory.makeTriggers()
                    val transactionFailExists =
                        triggers.contains(SuperwallEvents.TransactionFail.rawName)
                    if (options.paywalls.shouldShowPurchaseFailureAlert && !transactionFailExists) {
                        presentAlert(Error(result.errorMessage), product, purchaseSource.state)
                    } else {
                        updateState(
                            purchaseSource.paywallInfo.cacheKey,
                            PaywallViewState.Updates.ToggleSpinner(hidden = true),
                        )
                    }
                }
            }

            is PurchaseResult.Cancelled -> {
                trackCancelled(product, purchaseSource)
            }

            is PurchaseResult.Pending -> {
                handlePendingTransaction(purchaseSource)
            }
        }
        return result
    }

    private suspend fun didRestore(
        product: StoreProduct? = null,
        purchaseSource: PurchaseSource,
    ) {
        val restoreType: RestoreType =
            if (product != null) {
                // Product exists so much have been via a purchase of a specific product.
                RestoreType.ViaPurchase(awaitLatestTransaction())
            } else {
                // Otherwise it was a generic restore.
                RestoreType.ViaRestore
            }

        track(
            TransactionAnalytics.restored(
                product = product,
                restoreType = restoreType,
                purchaseSource = purchaseSource,
                observesPurchases = dependencies.observesPurchases,
                demand = dependencies.demand(),
            ),
        )

        val superwallOptions = factory.makeSuperwallOptions()
        if (superwallOptions.paywalls.automaticallyDismiss && purchaseSource is PurchaseSource.Internal) {
            dismiss(
                purchaseSource.paywallInfo.cacheKey,
                PaywallResult.Restored(),
            )
        }

        ioScope.launchWithTracking { notifyBackendOfReceipts() }
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
                            "paywall_state" to purchaseSource.state,
                        ),
                )

                ioScope.launchWithTracking {
                    track(failureEvent(errorMessage, product, purchaseSource))
                }
            }

            is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
                log(
                    message = "Transaction Error: $errorMessage",
                    info = mapOf("product_id" to product.fullIdentifier),
                    error = Error(errorMessage),
                )
                ioScope.launch {
                    track(failureEvent(errorMessage, product, purchaseSource))
                }
            }
        }
    }

    internal suspend fun prepareToPurchase(
        product: StoreProduct,
        source: PurchaseSource,
    ) {
        val isObserved =
            source is PurchaseSource.ObserverMode

        // The billing flow retains the last Play result indefinitely; clear it so this
        // purchase can't be resolved against a stale transaction from an earlier one.
        storeManager.billing.purchaseResults.value = null

        when (source) {
            is PurchaseSource.Internal -> {
                ioScope.launch {
                    log(
                        message =

                            "Transaction Purchasing",
                        info = mapOf("paywall_vc" to source),
                    )
                }

                track(TransactionAnalytics.start(product, source, dependencies.demand()))
            }

            is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
                if (isObserved && product.rawStoreProduct != null) {
                    transactionsInProgress.put(
                        product.fullIdentifier,
                        product.rawStoreProduct!!.underlyingProductDetails,
                    )
                }
                if (!storeManager.hasCached(product.fullIdentifier)) {
                    storeManager.cacheProduct(product.fullIdentifier, product)
                }

                if (factory.makeHasExternalPurchaseController() && !factory.makeHasInternalPurchaseController()) {
                    return
                }
                // If an external purchase controller is being used, skip because this will
                // get called by the purchase function of the purchase controller.
                val options = factory.makeSuperwallOptions()
                if (!options.shouldObservePurchases && factory.makeHasExternalPurchaseController()) {
                    return
                }

                ioScope.launch {
                    log(
                        message =

                            "External Transaction Purchasing",
                    )
                }

                track(TransactionAnalytics.start(product, source, dependencies.demand()))
            }
        }
    }

    private suspend fun didPurchase(
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        didStartFreeTrial: Boolean,
        purchase: Purchase? = null,
        shouldDismiss: Boolean,
    ) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                ioScope.launch {
                    log(
                        message = "Transaction Succeeded",
                        info =
                            mapOf(
                                "product_id" to product.fullIdentifier,
                                "paywall_state" to purchaseSource.state,
                            ),
                    )
                }

                val transaction = awaitLatestTransaction()

                storeManager.loadPurchasedProducts(allEntitlementsByProductId())

                ioScope.launchWithTracking { notifyBackendOfReceipts() }

                trackTransactionDidSucceed(transaction, product, purchaseSource, didStartFreeTrial)

                if (shouldDismiss && factory.makeSuperwallOptions().paywalls.automaticallyDismiss) {
                    dismiss(
                        purchaseSource.paywallInfo.cacheKey,
                        PaywallResult.Purchased(product.fullIdentifier),
                    )
                } else {
                    updateState(
                        purchaseSource.paywallInfo.cacheKey,
                        PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready),
                    )
                }
            }

            is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
                log(
                    message = "Transaction Succeeded",
                    info = mapOf("product_id" to product.fullIdentifier),
                )
                val transaction =
                    if (purchaseSource is PurchaseSource.ExternalPurchase) {
                        if (purchase != null) {
                            factory.makeStoreTransaction(purchase)
                        } else {
                            awaitLatestTransaction()
                        }
                    } else {
                        null
                    }
                storeManager.loadPurchasedProducts(allEntitlementsByProductId())

                ioScope.launchWithTracking { notifyBackendOfReceipts() }

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
                                "paywall_state" to purchaseSource.state,
                            ),
                    )
                }

                val paywallInfo = purchaseSource.paywallInfo
                track(TransactionAnalytics.abandon(product, purchaseSource, dependencies.demand()))

                notifyOfTransactionAbandon(paywallInfo.cacheKey)

                updateState(
                    paywallInfo.cacheKey,
                    PaywallViewState.Updates.SetLoadingState(
                        PaywallLoadingState.Ready,
                    ),
                )
            }

            is PurchaseSource.ExternalPurchase, is PurchaseSource.ObserverMode -> {
                ioScope.launch {
                    log(
                        message = "Transaction Abandoned",
                        info = mapOf("product_id" to product.fullIdentifier),
                    )
                }

                track(TransactionAnalytics.abandon(product, purchaseSource, dependencies.demand()))
            }
        }
    }

    private suspend fun handlePendingTransaction(purchaseSource: PurchaseSource) {
        when (purchaseSource) {
            is PurchaseSource.Internal -> {
                ioScope.launch {
                    log(
                        message = "Transaction Pending",
                        info = mapOf("paywall" to purchaseSource.paywallInfo),
                    )
                }

                track(pendingEvent(purchaseSource))

                showAlert(
                    AlertProps(
                        "Waiting for Approval",
                        "Thank you! This purchase is pending approval from your parent. Please try again once it is approved.",
                    ),
                )
            }

            is PurchaseSource.ExternalPurchase,
            is PurchaseSource.ObserverMode,
            -> {
                ioScope.launch {
                    log(message = "Transaction Pending")
                }

                track(pendingEvent(purchaseSource))
            }
        }
    }

    /**
     * Attempt to restore purchases.
     *
     * @param paywallView The paywall view that initiated the restore or null if initiated externally.
     * @param presentsFailureAlert When `false`, suppresses the SDK's own restore-failure and
     *   restore-from-web prompts. Used by callers, such as the Customer Center, that present their
     *   own restore-outcome UI.
     * @return A [RestorationResult] indicating the result of the restoration.
     */
    suspend fun tryToRestorePurchases(
        paywallView: PaywallView?,
        presentsFailureAlert: Boolean = true,
    ): RestorationResult {
        log(message = "Attempting Restore")

        // Test mode intercept: simulate restore without real billing
        if (testMode?.isTestMode == true && testModeTransactionHandler != null) {
            val result = testModeTransactionHandler.handleRestore()
            if (result is RestorationResult.Restored) {
                val status = testMode.buildSubscriptionStatus()
                setSubscriptionStatus?.invoke(status)
            }
            return result
        }

        val paywallInfo = paywallView?.state?.info ?: PaywallInfo.empty()
        paywallView?.updateState(PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.LoadingPurchase))

        track(TransactionAnalytics.restoreStart(paywallInfo))
        val restorationResult = purchaseController.restorePurchases()

        val hasRestored = restorationResult is RestorationResult.Restored
        val status = subscriptionStatus()
        val webEntitlements = webEntitlements()
        val hasEntitlements =
            status is SubscriptionStatus.Active || webEntitlements.isNotEmpty()
        storeManager.loadPurchasedProducts(allEntitlementsByProductId())

        val webToAppEnabled = factory.isWebToAppEnabled()
        val allPaywallEntitlements =
            paywallView
                ?.state
                ?.paywall
                ?.productIds
                ?.map {
                    entitlementsById(it)
                }?.flatten() ?: emptyList()
        val existingEntitlements =
            ((status as? SubscriptionStatus.Active)?.entitlements ?: emptySet()) + webEntitlements
        if (hasRestored && hasEntitlements) {
            if (existingEntitlements.containsAll(allPaywallEntitlements) || !webToAppEnabled) {
                log(message = "Transactions Restored")
                track(TransactionAnalytics.restoreComplete(paywallView?.state?.info ?: PaywallInfo.empty()))
                if (paywallView != null) {
                    didRestore(null, PurchaseSource.Internal("", paywallView.state))
                }
            } else {
                paywallView?.updateState(
                    PaywallViewState.Updates.SetLoadingState(
                        PaywallLoadingState.Ready,
                    ),
                )
                if (presentsFailureAlert) {
                    askToRestoreFromWeb()
                }
            }
        } else {
            val msg = TransactionAnalytics.restoreFailureMessage(restorationResult, hasEntitlements)
            log(message = msg)

            track(TransactionAnalytics.restoreFailure(msg, paywallView?.state?.info ?: PaywallInfo.empty()))
            if (presentsFailureAlert) {
                if (webToAppEnabled) {
                    askToRestoreFromWeb()
                } else {
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
            }
        }
        return restorationResult
    }

    private fun askToRestoreFromWeb() {
        val hasEntitlements =
            activeDeviceEntitlements().isNotEmpty()
        val hasSubsText =
            "Your Play Store subscriptions were restored. Would you like to check for more on the web?"
        val noSubsText = "No Play Store subscription found, would you like to check on the web?"

        showAlert(
            AlertProps(
                title =
                    if (hasEntitlements) "Restore via the web?" else "No Subscription Found",
                message =
                    if (hasEntitlements) hasSubsText else noSubsText,
                closeActionTitle =
                    "Close",
                actionTitle = "Yes",
                action = {
                    openRestoreOnWeb()
                },
            ),
        )
    }

    private suspend fun presentAlert(
        error: Error,
        product: StoreProduct,
        state: PaywallViewState,
    ) {
        ioScope.launch {
            log(
                message = "Transaction Error",
                info =
                    mapOf(
                        "product_id" to product.fullIdentifier,
                        "paywall_state" to state,
                    ),
                error = error,
            )
        }

        showAlert(
            AlertProps(
                "An error occurred",
                error.message ?: "Unknown error",
            ),
        )
    }

    // With an external purchase controller the purchase may not go through Google Play
    // at all (e.g. Galaxy Store), in which case the billing client never emits a result
    // and an unbounded wait would hang the purchase flow forever. The transaction only
    // enriches tracking events, so bound the wait and fall back to null.
    private suspend fun awaitLatestTransaction(): StoreTransaction? {
        val transactionVerifier = factory.makeTransactionVerifier()
        return if (factory.makeHasExternalPurchaseController()) {
            withTimeoutOrNull(TRANSACTION_LOOKUP_TIMEOUT_MS) {
                transactionVerifier.getLatestTransaction(factory)
            }
        } else {
            transactionVerifier.getLatestTransaction(factory)
        }
    }

    private suspend fun trackTransactionDidSucceed(
        transaction: StoreTransaction?,
        product: StoreProduct,
        purchaseSource: PurchaseSource,
        didStartFreeTrial: Boolean,
    ) {
        track(
            TransactionAnalytics.complete(
                product = product,
                transaction = transaction,
                purchaseSource = purchaseSource,
                demand = dependencies.demand(),
                userAttributes = factory.getCurrentUserAttributes(),
                storefrontCountryCode = storeManager.storefrontCountryCode,
            ),
        )
        eventsQueue.flushInternal()
        if (purchaseSource is PurchaseSource.Internal) {
            // Only report a trial end when a trial actually started; product metadata alone
            // can describe a trial the user was not eligible for.
            val trialEnd = if (didStartFreeTrial) product.trialPeriodEndDate?.time else null
            // Notify the paywall for every internal purchase, including one-time products,
            // so it can run its post-purchase actions.
            notifyOfTransactionComplete(
                purchaseSource.paywallInfo.cacheKey,
                trialEnd,
                product.fullIdentifier,
                didStartFreeTrial,
            )
        }
        track(TransactionAnalytics.purchaseKind(product, purchaseSource, didStartFreeTrial))
    }

    private fun failureEvent(
        errorMessage: String,
        product: StoreProduct,
        purchaseSource: PurchaseSource,
    ) = TransactionAnalytics.failure(
        errorMessage = errorMessage,
        product = product,
        purchaseSource = purchaseSource,
        observesPurchases = dependencies.observesPurchases,
        demand = dependencies.demand(),
    )

    private fun pendingEvent(purchaseSource: PurchaseSource) =
        TransactionAnalytics.pending(
            purchaseSource = purchaseSource,
            observesPurchases = dependencies.observesPurchases,
            demand = dependencies.demand(),
        )

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

    private companion object {
        const val TRANSACTION_LOOKUP_TIMEOUT_MS = 5_000L
    }
}
