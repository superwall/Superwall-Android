package com.superwall.sdk.store.transactions

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
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeTransactionHandler
import com.superwall.sdk.web.openRestoreOnWeb

class TransactionManager internal constructor(
    internal val dependencies: TransactionDependencies,
) {
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


    /** Runs purchases, deduplicates paywall purchases and holds what is in flight. */
    internal val purchaseFlow: PurchaseFlow

    /** Runs restores, including the restore-on-web prompt. */
    internal val restoreFlow = RestoreFlow(dependencies)

    /** Follows purchases the app makes itself (observer mode). */
    internal val observerModeTracker: ObserverModeTracker

    init {
        lateinit var tracker: ObserverModeTracker
        purchaseFlow = PurchaseFlow(dependencies, onObservedPurchase = { tracker.begin(it) })
        tracker =
            ObserverModeTracker(
                dependencies = dependencies,
                settle = { product, source, outcome -> purchaseFlow.settle(product, source, outcome) },
                restored = { product, source -> restoreFlow.restored(product, source) },
            )
        observerModeTracker = tracker
        observerModeTracker.start()
    }

    /** Acts on a Play [result] for the latest purchase the app made itself (observer mode). */
    internal suspend fun handle(
        result: InternalPurchaseResult,
        state: PurchasingObserverState,
    ) = observerModeTracker.handle(result, state)

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

    // A private companion const still compiles to a public static field on this class; it stays
    // so the binary API is unchanged. The lookup itself uses PurchaseFlow's timeout.
    @Suppress("unused")
    private companion object {
        const val TRANSACTION_LOOKUP_TIMEOUT_MS = 5_000L
    }
}
