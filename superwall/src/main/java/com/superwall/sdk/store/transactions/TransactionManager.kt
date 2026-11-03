package com.superwall.sdk.store.transactions

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
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
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.ProductReplacement
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeTransactionHandler
import com.superwall.sdk.web.openRestoreOnWeb
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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

    /** Runs purchases, deduplicates paywall purchases and holds what is in flight. */
    internal val purchaseFlow =
        PurchaseFlow(
            dependencies = dependencies,
            onObservedPurchase = { transactionsInProgress[it.productId] = it.details },
        )

    /** Runs restores, including the restore-on-web prompt. */
    internal val restoreFlow = RestoreFlow(dependencies)

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
                            purchaseFlow.settle(
                                product,
                                PurchaseSource.ObserverMode(product),
                                PurchaseOutcome.Purchased(product.hasFreeTrial, purchase),
                            )
                        }
                    }
                }
            }

            is InternalPurchaseResult.Cancelled -> {
                val product = StoreProduct(RawStoreProduct.from(lastProduct.value))
                purchaseFlow.settle(product, PurchaseSource.ObserverMode(product), PurchaseOutcome.Cancelled)
                transactionsInProgress.remove(lastProduct.key)
            }

            is InternalPurchaseResult.Failed -> {
                transactionsInProgress.remove(lastProduct.key)
                val state = state as PurchasingObserverState.PurchaseError
                val product = StoreProduct(RawStoreProduct.from(state.product))
                val message =
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
                        }?.message ?: "Unknown error"
                purchaseFlow.settle(product, PurchaseSource.ObserverMode(product), PurchaseOutcome.Failed(message))
            }

            InternalPurchaseResult.Pending -> {
                val result = state as PurchasingObserverState.PurchaseResult
                transactionsInProgress.remove(lastProduct.key)
                result.purchases?.forEach { purchase ->
                    purchase.products.map {
                        storeManager.getProductFromCache(it)?.let { product ->
                            purchaseFlow.settle(product, PurchaseSource.ObserverMode(product), PurchaseOutcome.Pending)
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
                            restoreFlow.restored(product, PurchaseSource.ObserverMode(product))
                        }
                    }
                }
            }
        }
    }

    fun updatePaymentQueue(removedTransactions: List<String>) {
        purchaseFlow.clearPurchasingIds(removedTransactions)
    }

    suspend fun purchase(
        purchaseSource: PurchaseSource,
        shouldDismiss: Boolean = true,
    ): PurchaseResult = purchaseFlow.purchase(purchaseSource, shouldDismiss)

    /**
     * Purchases from a paywall. [onAdmitted] runs only when no other paywall purchase is running,
     * so the paywall shows its loading state for the purchase that actually starts.
     */
    internal suspend fun purchase(
        purchaseSource: PurchaseSource,
        shouldDismiss: Boolean,
        onAdmitted: () -> Unit,
    ): PurchaseResult = purchaseFlow.purchase(purchaseSource, shouldDismiss, onAdmitted)

    internal suspend fun prepareToPurchase(
        product: StoreProduct,
        source: PurchaseSource,
    ) = purchaseFlow.prepareToPurchase(product, source)

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
    ): RestorationResult = restoreFlow.restore(paywallView, presentsFailureAlert)

    private fun log(
        logLevel: LogLevel = LogLevel.debug,
        message: String,
        info: Map<String, Any>? = null,
        error: Throwable? = null,
    ) = transactionLog(logLevel, message, info, error)

    // A private companion const still compiles to a public static field on this class; it stays
    // so the binary API is unchanged. The lookup itself uses PurchaseFlow's timeout.
    @Suppress("unused")
    private companion object {
        const val TRANSACTION_LOOKUP_TIMEOUT_MS = 5_000L
    }
}
