package com.superwall.sdk.store.transactions

import com.superwall.sdk.billing.BillingBackedStore
import com.superwall.sdk.billing.Store
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.AlertControllerFactory.AlertProps
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeTransactionHandler

/**
 * Everything the transaction flows read or call outside themselves, gathered in one value.
 *
 * It replaces the long list of constructor lambdas [TransactionManager] used to take, and is
 * shared by the purchase, restore and observer-mode contexts so each sees the same collaborators.
 */
internal data class TransactionDependencies(
    val storeManager: StoreManager,
    val purchaseController: PurchaseController,
    val eventsQueue: EventsQueue,
    val storage: Storage,
    val activityProvider: ActivityProvider,
    val factory: TransactionManager.Factory,
    val ioScope: IOScope,
    val track: suspend (TrackableSuperwallEvent) -> Unit,
    val dismiss: suspend (paywallId: String, result: PaywallResult) -> Unit,
    val showAlert: (AlertProps) -> Unit,
    val subscriptionStatus: () -> SubscriptionStatus,
    val entitlementsById: (String) -> Set<Entitlement>,
    val allEntitlementsByProductId: () -> Map<String, Set<Entitlement>>,
    val webEntitlements: () -> Set<Entitlement>,
    val currentCustomerInfo: () -> CustomerInfo,
    val showRestoreDialogForWeb: suspend () -> Unit,
    val notifyBackendOfReceipts: suspend () -> Unit,
    val refreshReceipt: () -> Unit,
    val updateState: (cacheKey: String, update: PaywallViewState.Updates) -> Unit,
    val notifyOfTransactionComplete: suspend (
        paywallCacheKey: String,
        trialEndDate: Long?,
        productId: String,
        didStartFreeTrial: Boolean,
    ) -> Unit,
    val notifyOfTransactionAbandon: suspend (paywallCacheKey: String) -> Unit,
    val testMode: TestMode?,
    val testModeTransactionHandler: TestModeTransactionHandler?,
    val setSubscriptionStatus: ((SubscriptionStatus) -> Unit)?,
    val activeDeviceEntitlements: () -> Set<Entitlement>,
    val openRestoreOnWeb: () -> Unit,
) {
    /** The demand enrichment for the next event, read fresh each time as the factory may change it. */
    fun demand(): TransactionAnalytics.Demand =
        TransactionAnalytics.Demand(score = factory.demandScore(), tier = factory.demandTier())

    val observesPurchases: Boolean
        get() = factory.makeSuperwallOptions().shouldObservePurchases

    private val storeAndPurchases by lazy { BillingBackedStore.storeOf(storeManager.billing) }

    /** The store the purchases are made through, behind the store manager's billing. */
    val store: Store
        get() = storeAndPurchases.first

    /**
     * The SDK transaction for [purchase], built from the original store purchase it came from,
     * or `null` when that purchase was never seen.
     */
    suspend fun storeTransactionOf(purchase: StorePurchase): StoreTransaction? =
        storeAndPurchases.second.transactionFor(purchase, factory::makeStoreTransaction)
}

/**
 * Shared shape of the three transaction contexts: storage and tracking come from
 * [TransactionDependencies], so a context only has to supply its actor and scope.
 */
internal interface TransactionDependenciesContext<S, Self : BaseContext<S, Self>> : BaseContext<S, Self> {
    val dependencies: TransactionDependencies

    override val storage: Storage get() = dependencies.storage

    override val tracker: suspend (TrackableSuperwallEvent) -> Unit get() = dependencies.track
}

/** Context for purchase [TransactionState.Actions]: start, result, pending and cancel. */
internal interface TransactionContext : TransactionDependenciesContext<TransactionState, TransactionContext> {
    /** Tells the observer-mode tracker about a purchase the app started itself. */
    fun onObservedPurchaseStarted(purchase: ObservedPurchase)
}

/** Context for restore [RestoreState.Actions], including the restore-on-web prompt. */
internal interface RestoreContext : TransactionDependenciesContext<RestoreState, RestoreContext>

/** Context for purchases the SDK observes but does not start (observer mode). */
internal interface ObserverModeContext : TransactionDependenciesContext<ObserverModeState, ObserverModeContext> {
    /** Hands the end of an observed purchase to the purchase flow, which tracks it. */
    suspend fun settleObservedPurchase(
        product: StoreProduct,
        source: TransactionManager.PurchaseSource,
        outcome: PurchaseOutcome,
    )

    /** Hands an observed purchase that turned out to restore [product] to the restore flow. */
    suspend fun restoredByObservedPurchase(
        product: StoreProduct,
        source: TransactionManager.PurchaseSource,
    )
}
