package com.superwall.sdk.store.transactions

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.AlertControllerFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.products.mockProductDetails
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.PurchasingProductdIds
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.MockKAnnotations
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TransactionManagerTest {
    @MockK(relaxed = true)
    private lateinit var storeManager: StoreManager

    @MockK(relaxed = true)
    private lateinit var purchaseController: PurchaseController

    @MockK(relaxed = true)
    private lateinit var eventsQueue: EventsQueue

    @MockK(relaxed = true)
    private lateinit var storage: Storage

    @MockK(relaxed = true)
    private lateinit var activityProvider: ActivityProvider

    @MockK(relaxed = true)
    private lateinit var factory: TransactionManager.Factory

    private lateinit var ioScope: IOScope
    private lateinit var transactionManager: TransactionManager
    private lateinit var productsByFullId: MutableMap<String, StoreProduct>

    private val trackedEvents = mutableListOf<TrackableSuperwallEvent>()
    private val dismissCalls = mutableListOf<Pair<String, PaywallResult>>()
    private val alertCalls = mutableListOf<AlertControllerFactory.AlertProps>()
    private val stateUpdates = mutableListOf<Pair<String, PaywallViewState.Updates>>()
    private val transactionCompleteCalls = mutableListOf<Pair<String, Long?>>()
    private var subscriptionStatusValue: SubscriptionStatus = SubscriptionStatus.Active(setOf(Entitlement("test")))
    private var webEntitlementsValue: Set<Entitlement> = emptySet()
    private val entitlementsMap = mutableMapOf<String, Set<Entitlement>>()
    private var refreshReceiptCalled = false
    private var showRestoreDialogCalled = false

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        trackedEvents.clear()
        dismissCalls.clear()
        alertCalls.clear()
        stateUpdates.clear()
        transactionCompleteCalls.clear()
        refreshReceiptCalled = false
        showRestoreDialogCalled = false
        webEntitlementsValue = emptySet()
        subscriptionStatusValue = SubscriptionStatus.Active(setOf(Entitlement("test")))
        entitlementsMap.clear()

        ioScope = IOScope(Dispatchers.Unconfined)
        productsByFullId = mutableMapOf()

        val billing =
            mockk<com.superwall.sdk.billing.Billing> {
                every { purchaseResults } returns MutableStateFlow(null)
            }

        every { storeManager.billing } returns billing

        every { factory.makeSuperwallOptions() } returns
            mockk(relaxed = true) {
                every { shouldObservePurchases } returns false
            }

        transactionManager =
            TransactionManager(
                storeManager = storeManager,
                purchaseController = purchaseController,
                eventsQueue = eventsQueue,
                storage = storage,
                activityProvider = activityProvider,
                factory = factory,
                ioScope = ioScope,
                track = { trackedEvents.add(it) },
                dismiss = { id, result -> dismissCalls.add(id to result) },
                showAlert = { alertCalls.add(it) },
                subscriptionStatus = { subscriptionStatusValue },
                entitlementsById = { entitlementsMap[it] ?: emptySet() },
                allEntitlementsByProductId = { entitlementsMap },
                webEntitlements = { webEntitlementsValue },
                showRestoreDialogForWeb = { showRestoreDialogCalled = true },
                refreshReceipt = { refreshReceiptCalled = true },
                updateState = { key, update -> stateUpdates.add(key to update) },
                notifyOfTransactionComplete = { cacheKey, trialEndDate, id -> transactionCompleteCalls.add(cacheKey to trialEndDate) },
            )
    }

    @After
    fun teardown() {
        clearMocks(storeManager, factory, storage, answers = false)
    }

    @Test
    fun updatePaymentQueue_removesTransactionsFromStorage() {
        Given("stored purchasing ids contain completed transactions") {
            every { storage.read(PurchasingProductdIds) } returns setOf("txn1", "txn2", "txn3", "txn4")

            When("the queue is updated with the completed ids") {
                transactionManager.updatePaymentQueue(listOf("txn1", "txn2", "txn3"))

                Then("only remaining transactions are persisted") {
                    verify(exactly = 1) {
                        storage.write(PurchasingProductdIds, setOf("txn4"))
                    }
                }
            }
        }
    }

    @Test
    fun updatePaymentQueue_withEmptyRemovalList_persistsExistingState() {
        Given("storage returns the current purchasing ids") {
            val stored = setOf("txn1", "txn2")
            every { storage.read(PurchasingProductdIds) } returns stored

            When("no transactions are removed") {
                transactionManager.updatePaymentQueue(emptyList())

                Then("the original set is written back unchanged") {
                    verify(exactly = 1) {
                        storage.write(PurchasingProductdIds, stored)
                    }
                }
            }
        }
    }

    @Test
    fun updatePaymentQueue_handlesMissingStorageState() {
        Given("storage has no purchasing ids stored") {
            every { storage.read(PurchasingProductdIds) } returns null

            When("the queue is updated") {
                transactionManager.updatePaymentQueue(listOf("txn1"))

                Then("an empty set is persisted") {
                    verify {
                        storage.write(PurchasingProductdIds, emptySet())
                    }
                }
            }
        }
    }

    @Test
    fun handle_purchased_refreshesReceiptAndTracksEvent() =
        runTest {
            Given("a transaction in progress that completes successfully") {
                val productId = "product1"
                val productDetails = mockProductDetails(productId = productId)
                val storeProduct = mockStoreProduct(productId = productId)
                productsByFullId[productId] = storeProduct
                addTransactionInProgress(productId, productDetails)

                val purchase =
                    mockk<Purchase>(relaxed = true) {
                        every { products } returns listOf(productId)
                    }
                val result = InternalPurchaseResult.Purchased(purchase)
                val state =
                    PurchasingObserverState.PurchaseResult(
                        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                        listOf(purchase),
                    )

                When("handle is invoked") {
                    transactionManager.handle(result, state)

                    Then("the receipt is refreshed and a transaction event is tracked") {
                        assertTrue(refreshReceiptCalled)
                        assertTrue(trackedEvents.any { it is InternalSuperwallEvent.Transaction })
                        And("the in-progress transaction is cleared") {
                            assertFalse(hasTransactionInProgress(productId))
                        }
                    }
                }
            }
        }

    @Test
    fun handle_cancelled_tracksAbandonEvent() =
        runTest {
            Given("a pending transaction that gets cancelled") {
                val productId = "product2"
                val productDetails = mockProductDetails(productId = productId)
                val rawProduct = mockk<RawStoreProduct>(relaxed = true)
                mockkObject(RawStoreProduct.Companion)
                try {
                    every { RawStoreProduct.from(productDetails) } returns rawProduct
                    val storeProduct = mockStoreProduct(productId = productId, rawProduct = rawProduct)
                    productsByFullId[productId] = storeProduct
                    addTransactionInProgress(productId, productDetails)

                    val result = InternalPurchaseResult.Cancelled
                    val state =
                        PurchasingObserverState.PurchaseResult(
                            BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED).build(),
                            emptyList(),
                        )

                    When("handle processes the cancellation") {
                        transactionManager.handle(result, state)

                        Then("an abandon event is recorded and the transaction is cleared") {
                            val abandonEvents =
                                trackedEvents
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .map { it.state }
                            assertTrue(abandonEvents.any { it is InternalSuperwallEvent.Transaction.State.Abandon })
                            assertFalse(hasTransactionInProgress(productId))
                        }
                    }
                } finally {
                    unmockkObject(RawStoreProduct.Companion)
                }
            }
        }

    @Test
    fun handle_failed_tracksFailureEvent() =
        runTest {
            Given("a transaction that fails with an error") {
                val productId = "product3"
                val productDetails = mockProductDetails(productId = productId)
                val rawProduct = mockk<RawStoreProduct>(relaxed = true)
                mockkObject(RawStoreProduct.Companion)
                try {
                    every { RawStoreProduct.from(productDetails) } returns rawProduct
                    val storeProduct = mockStoreProduct(productId = productId, rawProduct = rawProduct)
                    productsByFullId[productId] = storeProduct
                    addTransactionInProgress(productId, productDetails)

                    val error = Throwable("Payment failed")
                    val result = InternalPurchaseResult.Failed(error)
                    val state = PurchasingObserverState.PurchaseError(productDetails, error)

                    When("handle processes the failure") {
                        transactionManager.handle(result, state)

                        Then("a failure event is recorded") {
                            val failEvents =
                                trackedEvents
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .map { it.state }
                            assertTrue(
                                failEvents.any {
                                    it is InternalSuperwallEvent.Transaction.State.Fail &&
                                        it.error is TransactionError.Failure
                                },
                            )
                        }
                    }
                } finally {
                    unmockkObject(RawStoreProduct.Companion)
                }
            }
        }

    @Test
    fun handle_pending_recordsPendingState() =
        runTest {
            Given("a transaction that becomes pending") {
                val productId = "product4"
                val productDetails = mockProductDetails(productId = productId)
                val storeProduct = mockStoreProduct(productId = productId)
                productsByFullId[productId] = storeProduct
                addTransactionInProgress(productId, productDetails)

                val purchase =
                    mockk<Purchase>(relaxed = true) {
                        every { products } returns listOf(productId)
                    }
                val result = InternalPurchaseResult.Pending
                val state =
                    PurchasingObserverState.PurchaseResult(
                        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                        listOf(purchase),
                    )

                When("handle processes the pending state") {
                    transactionManager.handle(result, state)

                    Then("a pending failure event is tracked") {
                        val failEvents =
                            trackedEvents
                                .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                .map { it.state }
                        assertTrue(
                            failEvents.any {
                                it is InternalSuperwallEvent.Transaction.State.Fail &&
                                    it.error is TransactionError.Pending
                            },
                        )
                    }
                }
            }
        }

    @Test
    fun handle_restored_tracksRestoreEvent() =
        runTest {
            Given("a transaction that is restored") {
                val productId = "product5"
                val productDetails = mockProductDetails(productId = productId)
                val storeProduct = mockStoreProduct(productId = productId)
                productsByFullId[productId] = storeProduct
                addTransactionInProgress(productId, productDetails)

                val purchase =
                    mockk<Purchase>(relaxed = true) {
                        every { products } returns listOf(productId)
                    }
                val result = InternalPurchaseResult.Restored
                val state =
                    PurchasingObserverState.PurchaseResult(
                        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                        listOf(purchase),
                    )

                When("handle processes the restore result") {
                    transactionManager.handle(result, state)

                    Then("a restore event is recorded") {
                        val restoreEvents =
                            trackedEvents
                                .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                .map { it.state }
                        assertTrue(restoreEvents.any { it is InternalSuperwallEvent.Transaction.State.Restore })
                    }
                }
            }
        }

    @Test
    fun handle_withEmptyTransactions_doesNothing() =
        runTest {
            Given("no transactions are tracked") {
                val result = InternalPurchaseResult.Purchased(mockk(relaxed = true))
                val state =
                    PurchasingObserverState.PurchaseResult(
                        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                        emptyList(),
                    )

                When("handle is invoked") {
                    transactionManager.handle(result, state)

                    Then("the receipt is not refreshed") {
                        assertFalse(refreshReceiptCalled)
                    }
                }
            }
        }

    // region app2web restore regression (external purchase controller)

    @Test
    fun tryToRestore_restoredWithWebEntitlementsButInactiveStatus_tracksRestoreComplete() =
        runTest {
            Given("a restore that succeeds while the SDK subscription status is inactive but the user holds web-granted entitlements covering the paywall") {
                // Reproduces the app2web + external purchase controller bug: a successful web
                // redemption triggers a native restore. The redeemed Stripe entitlement lives in
                // the web entitlements (merged CustomerInfo) but was never written into
                // SubscriptionStatus, so the restore used to be reported as a failure and show the
                // "No Subscription Found" dialog.
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()
                subscriptionStatusValue = SubscriptionStatus.Inactive
                webEntitlementsValue = setOf(Entitlement("standard"))
                entitlementsMap["price_stripe_standard"] = setOf(Entitlement("standard"))
                every { factory.isWebToAppEnabled() } returns true

                val paywallView =
                    mockk<PaywallView>(relaxed = true) {
                        every { state.paywall.productIds } returns listOf("price_stripe_standard")
                    }

                When("a restore is attempted") {
                    transactionManager.tryToRestorePurchases(paywallView)

                    Then("the restore is reported as complete and not as a failure") {
                        val restoreStates =
                            trackedEvents
                                .filterIsInstance<InternalSuperwallEvent.Restore>()
                                .map { it.state }
                        assertTrue(restoreStates.any { it is InternalSuperwallEvent.Restore.State.Complete })
                        assertFalse(restoreStates.any { it is InternalSuperwallEvent.Restore.State.Failure })
                    }
                }
            }
        }

    @Test
    fun tryToRestore_restoredWithNoEntitlementsAnywhere_tracksRestoreFailure() =
        runTest {
            Given("a restore that succeeds but the user has neither active status nor web entitlements") {
                // Control: guards against over-fixing. A genuine "nothing to restore" must still fail.
                // webToApp disabled so the failure path shows the alert instead of askToRestoreFromWeb
                // (which would touch Superwall.instance).
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()
                subscriptionStatusValue = SubscriptionStatus.Inactive
                webEntitlementsValue = emptySet()
                every { factory.isWebToAppEnabled() } returns false

                val paywallView =
                    mockk<PaywallView>(relaxed = true) {
                        every { state.paywall.productIds } returns emptyList()
                    }

                When("a restore is attempted") {
                    transactionManager.tryToRestorePurchases(paywallView)

                    Then("the restore is reported as a failure") {
                        val restoreStates =
                            trackedEvents
                                .filterIsInstance<InternalSuperwallEvent.Restore>()
                                .map { it.state }
                        assertTrue(restoreStates.any { it is InternalSuperwallEvent.Restore.State.Failure })
                        assertFalse(restoreStates.any { it is InternalSuperwallEvent.Restore.State.Complete })
                    }
                }
            }
        }

    // endregion

    private fun mockStoreProduct(
        productId: String,
        rawProduct: RawStoreProduct? = null,
    ): StoreProduct {
        val underlyingRaw =
            rawProduct
                ?: mockk(relaxed = true) {
                    every { fullIdentifier } returns productId
                    every { productIdentifier } returns productId
                }
        return mockk(relaxed = true) {
            every { fullIdentifier } returns productId
            every { hasFreeTrial } returns false
            every { rawStoreProduct } returns underlyingRaw
        }
    }

    private fun addTransactionInProgress(
        productId: String,
        productDetails: ProductDetails,
    ) {
        val field = TransactionManager::class.java.getDeclaredField("transactionsInProgress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(transactionManager) as ConcurrentHashMap<String, ProductDetails>
        map[productId] = productDetails
    }

    private fun hasTransactionInProgress(productId: String): Boolean {
        val field = TransactionManager::class.java.getDeclaredField("transactionsInProgress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(transactionManager) as ConcurrentHashMap<String, ProductDetails>
        return map.containsKey(productId)
    }
}
