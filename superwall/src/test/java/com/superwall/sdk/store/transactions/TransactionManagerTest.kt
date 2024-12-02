package com.superwall.sdk.store.transactions

import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.EntitlementStatus
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.Offer
import com.superwall.sdk.models.product.PlayStoreProduct
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.products.mockPricingPhase
import com.superwall.sdk.products.mockSubscriptionOfferDetails
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.InternalPurchaseController
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.product.OfferType
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.ref.WeakReference

class TransactionManagerTest {
    private val playProduct =
        mockk<ProductDetails> {
            every { productId } returns "product1"
            every { oneTimePurchaseOfferDetails } returns
                mockk {
                    every { priceAmountMicros } returns 1000000
                    every { priceCurrencyCode } returns "USD"
                }
        }

    val entitlements = setOf("test-entitlement").map { Entitlement(it) }.toSet()

    private val mockProduct =
        RawStoreProduct(
            playProduct,
            "product1",
            "basePlan",
            OfferType.Auto,
        )

    private val mockItems =
        listOf(
            ProductItem(
                "Item1",
                ProductItem.StoreProductType.PlayStore(
                    PlayStoreProduct(
                        productIdentifier = "product1",
                        basePlanIdentifier = "basePlan",
                        offer = Offer.Automatic(),
                    ),
                ),
                entitlements = entitlements.map { it.id }.toSet(),
            ),
        )

    private val pwInfo =
        PaywallInfo.empty().copy(
            products =
            mockItems,
        )

    private val mockedPaywall: Paywall =
        mockk {
            every { getInfo(any()) } returns pwInfo
            every { productIds } returns listOf("product1")
            every { productItems } returns mockItems
            every { isFreeTrialAvailable } returns true
        }
    private val paywallView =
        mockk<PaywallView>(relaxUnitFun = true) {
            every { info } returns pwInfo
            every { paywall } returns mockedPaywall
        }

    private var purchaseController = mockk<InternalPurchaseController>()
    private var billing: Billing =
        mockk {
            coEvery { awaitGetProducts(any()) } returns
                setOf(
                    StoreProduct(
                        mockProduct,
                    ),
                )
        }
    private var storeKitManager = spyk(StoreKitManager(purchaseController, billing))
    private var activityProvider =
        mockk<ActivityProvider> {
            every { getCurrentActivity() } returns mockk()
        }

    private var eventsQueue = mockk<EventsQueue>(relaxUnitFun = true)
    private var transactionManagerFactory =
        mockk<TransactionManager.Factory> {
            every { makeTransactionVerifier() } returns
                mockk {
                    coEvery { getLatestTransaction(any()) } returns mockk()
                }
            every { makeHasExternalPurchaseController() } returns false
            every { makeSuperwallOptions() } returns SuperwallOptions()
        }

    private var storage = mockk<Storage>(relaxUnitFun = true)

    fun TestScope.manager(
        track: (TrackableSuperwallEvent) -> Unit = {},
        dismiss: (paywallView: PaywallView, result: PaywallResult) -> Unit = { _, _ -> },
        entitlementStatus: () -> EntitlementStatus = {
            EntitlementStatus.Active(entitlements)
        },
        options: SuperwallOptions.() -> Unit = {},
    ) = TransactionManager(
        purchaseController = purchaseController,
        storeKitManager = storeKitManager,
        activityProvider = activityProvider,
        entitlementStatus = entitlementStatus,
        track = { track(it) },
        dismiss = { i, e -> dismiss(i, e) },
        eventsQueue = eventsQueue,
        factory = transactionManagerFactory,
        ioScope = IOScope(this.coroutineContext),
        storage = storage,
    ).also {
        coEvery { transactionManagerFactory.makeSuperwallOptions() } returns
            SuperwallOptions().apply(
                options,
            )
    }

    @Test
    fun test_purchase_internal_product_not_found() =
        runTest {
            Given("We try to purchase a product that does not exist") {
                val transactionManager: TransactionManager = manager()
                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase fails") {
                        assert(result is PurchaseResult.Failed && result.errorMessage == "Product not found")
                    }
                }
            }
        }

    @Test
    fun test_purchase_activity_not_found() =
        runTest {
            Given("We have loaded products but no activity") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                every { activityProvider.getCurrentActivity() } returns null
                val transactionManager: TransactionManager = manager()
                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase fails") {
                        assert(
                            result is PurchaseResult.Failed &&
                                result.errorMessage == "Activity not found - required for starting the billing flow",
                        )
                    }
                }
            }
        }

    @Test
    fun test_purchase_successful_internal() =
        runTest {
            val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
            Given("We have loaded products and we can purchase successfully") {
                // Pretend a paywall loaded a product
                storeKitManager.getProducts(paywall = mockedPaywall)
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update {
                            it + e
                        }
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify event order") {
                            val transactionEvents =
                                events.value.filterIsInstance<InternalSuperwallEvent.Transaction>()

                            assert(transactionEvents.first().superwallEvent is SuperwallEvent.TransactionStart)
                            assert(transactionEvents.first().product?.fullIdentifier == "product1")
                            assert(transactionEvents.last().superwallEvent is SuperwallEvent.TransactionComplete)

                            val purchase =
                                events.value.filterIsInstance<InternalSuperwallEvent.NonRecurringProductPurchase>()
                            assert(purchase.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_successful_external() =
        runTest {
            Given("We have loaded products and we can purchase successfully externally") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    }, options = {
                        paywalls.automaticallyDismiss = true
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase a product externally") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.ExternalPurchase(
                                StoreProduct(mockProduct),
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify event order") {
                            val transactionEvents =
                                events.value.filterIsInstance<InternalSuperwallEvent.Transaction>()
                            assert(transactionEvents.first().superwallEvent is SuperwallEvent.TransactionStart)
                            assert(transactionEvents.last().superwallEvent is SuperwallEvent.TransactionComplete)

                            val purchase =
                                events.value.filterIsInstance<InternalSuperwallEvent.NonRecurringProductPurchase>()
                            assert(purchase.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_restored_internal() =
        runTest {
            Given("We have loaded products and a purchase results in restoration") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(
                        track = { e ->
                            events.update { it + e }
                        },
                        options = {
                            paywalls.automaticallyDismiss = true
                        },
                        dismiss = { view, res ->
                            assert(view == paywallView)
                            assert(res is PaywallResult.Restored)
                        },
                    )
                coEvery {
                    purchaseController.restorePurchases()
                } returns RestorationResult.Restored()

                When("We try to restore a product from the paywall") {
                    val result =
                        transactionManager.tryToRestorePurchases(
                            paywallView,
                        )
                    Then("The restore results in restoration") {
                        assert(result is RestorationResult.Restored)
                        And("Verify restoration event") {
                            val restorationEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .find { it.state is InternalSuperwallEvent.Transaction.State.Restore }
                            assert(restorationEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_restored_external() =
        runTest {
            Given("We want to restore a product externally") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                coEvery {
                    purchaseController.restorePurchases()
                } returns RestorationResult.Restored()

                When("We try to restore a product from the paywall") {
                    val result = transactionManager.tryToRestorePurchases(null)
                    Then("The purchase results in restoration") {
                        assert(result is RestorationResult.Restored)
                        And("Verify restoration event") {
                            advanceUntilIdle()
                            val restorationEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Restore>()
                                    .find { it.state is InternalSuperwallEvent.Restore.State.Complete }
                            assert(restorationEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_failed_with_alert() =
        runTest {
            Given("We have loaded products and a purchase fails") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    }, options = {
                        paywalls.shouldShowPurchaseFailureAlert = true
                    })
                coEvery { transactionManagerFactory.makeTriggers() } returns
                    events.value
                        .map { it.rawName }
                        .toSet()
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Failed("Test failure")

                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase fails and an alert is shown") {
                        assert(result is PurchaseResult.Failed)
                        coVerify {
                            paywallView.showAlert(
                                any(),
                                any(),
                                any(),
                                any(),
                                isNull(),
                                isNull(),
                            )
                        }
                        And("Verify failure event") {
                            val failureEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .find { it.state is InternalSuperwallEvent.Transaction.State.Fail }
                            assert(failureEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_failed_without_alert() =
        runTest {
            Given("We have loaded products and a purchase fails") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    }, options = {
                        paywalls.shouldShowPurchaseFailureAlert = false
                    })
                coEvery { transactionManagerFactory.makeTriggers() } returns
                    events.value
                        .map { it.rawName }
                        .toSet()
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Failed("Test failure")

                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase fails and no alert is shown") {
                        assert(result is PurchaseResult.Failed)
                        coVerify(exactly = 0) {
                            paywallView.showAlert(
                                any(),
                                any(),
                                any(),
                                any(),
                                isNull(),
                                isNull(),
                            )
                        }
                        And("Verify failure event") {
                            advanceUntilIdle()
                            val failureEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .find { it.state is InternalSuperwallEvent.Transaction.State.Fail }
                            assert(failureEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_pending() =
        runTest {
            Given("We have loaded products and a purchase is pending") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Pending()

                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase is pending") {
                        assert(result is PurchaseResult.Pending)
                        coVerify { paywallView.showAlert(any(), any()) }
                        And("Verify pending event") {
                            val pendingEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .find {
                                        it.state is InternalSuperwallEvent.Transaction.State.Fail &&
                                            (it.state as InternalSuperwallEvent.Transaction.State.Fail).error is TransactionError.Pending
                                    }
                            assert(pendingEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_cancelled_internal() =
        runTest {
            Given("We have loaded products and a purchase is pending") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Cancelled()

                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase is pending") {
                        assert(result is PurchaseResult.Cancelled)
                        verify { paywallView setProperty "loadingState" value any(PaywallLoadingState.Ready::class) }
                        And("Verify pending event") {
                            val pendingEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .find { it.state is InternalSuperwallEvent.Transaction.State.Abandon }
                            assert(pendingEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_cancelled_external() =
        runTest {
            Given("An external purchase was cancelled") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns PurchaseResult.Cancelled()

                When("We try to purchase a product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.ExternalPurchase(
                                StoreProduct(mockProduct),
                            ),
                        )
                    Then("The purchase is cancelled") {
                        assert(result is PurchaseResult.Cancelled)
                        verify(exactly = 0) {
                            paywallView setProperty "loadingState" value
                                any(
                                    PaywallLoadingState.Ready::class,
                                )
                        }
                        And("Verify pending event") {
                            val pendingEvent =
                                events.value
                                    .filterIsInstance<InternalSuperwallEvent.Transaction>()
                                    .find { it.state is InternalSuperwallEvent.Transaction.State.Abandon }
                            assert(pendingEvent != null)
                        }
                    }
                }
            }
        }

    @Test
    fun test_try_to_restore_purchases_success() =
        runTest {
            Given("We can successfully restore purchases from a paywall") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(
                        track = { e ->
                            events.update { it + e }
                        },
                        entitlementStatus = { EntitlementStatus.Active(entitlements) },
                    )
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()

                When("We try to restore purchases") {
                    val result = transactionManager.tryToRestorePurchases(paywallView)
                    Then("The restoration is successful") {
                        assert(result is RestorationResult.Restored)
                        And("Verify restoration events") {
                            val restoreEvents =
                                events.value.filterIsInstance<InternalSuperwallEvent.Restore>()
                            assert(restoreEvents.size == 2)
                            assert(restoreEvents[0].state is InternalSuperwallEvent.Restore.State.Start)
                            assert(restoreEvents[1].state is InternalSuperwallEvent.Restore.State.Complete)
                        }
                    }
                }
            }
        }

    @Test
    fun test_try_to_restore_purchases_success_externally() =
        runTest {
            Given("We can successfully restore purchases without a paywall") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(
                        track = { e ->
                            events.update { it + e }
                        },
                        entitlementStatus = { EntitlementStatus.Active(entitlements) },
                    )
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()

                When("We try to restore purchases") {
                    val result = transactionManager.tryToRestorePurchases(null)
                    Then("The restoration is successful") {
                        assert(result is RestorationResult.Restored)
                        And("Verify restoration events") {
                            val restoreEvents =
                                events.value.filterIsInstance<InternalSuperwallEvent.Restore>()
                            assert(restoreEvents.size == 2)
                            assert(restoreEvents[0].state is InternalSuperwallEvent.Restore.State.Start)
                            assert(restoreEvents[1].state is InternalSuperwallEvent.Restore.State.Complete)
                        }
                    }
                }
            }
        }

    @Test
    fun test_try_to_restore_purchases_failure() =
        runTest {
            Given("Restoration of purchases fails") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(
                        track = { e ->
                            events.update { it + e }
                        },
                        entitlementStatus = { EntitlementStatus.NoActiveEntitlements },
                    )

                coEvery { purchaseController.restorePurchases() } returns
                    RestorationResult.Failed(
                        Exception("Test failure"),
                    )

                When("We try to restore purchases") {
                    val result = transactionManager.tryToRestorePurchases(paywallView)
                    Then("The restoration fails") {
                        assert(result is RestorationResult.Failed)
                        coVerify {
                            paywallView.showAlert(
                                any(),
                                any(),
                                any(),
                                any(),
                                isNull(),
                                isNull(),
                            )
                        }
                        And("Verify restoration events") {
                            val restoreEvents =
                                events.value.filterIsInstance<InternalSuperwallEvent.Restore>()
                            assert(restoreEvents.size == 2)
                            assert(restoreEvents[0].state is InternalSuperwallEvent.Restore.State.Start)
                            assert(restoreEvents[1].state is InternalSuperwallEvent.Restore.State.Failure)
                        }
                    }
                }
            }
        }

    @Test
    fun test_try_to_restore_purchases_restored_but_inactive() =
        runTest {
            Given("Restoration of purchases fails") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(
                        track = { e ->
                            events.update { it + e }
                        },
                        entitlementStatus = { EntitlementStatus.NoActiveEntitlements },
                    )

                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()

                When("We try to restore purchases") {
                    val result = transactionManager.tryToRestorePurchases(paywallView)
                    Then("The restoration fails because subscription is inactive") {
                        assert(result is RestorationResult.Restored)
                        coVerify {
                            paywallView.showAlert(
                                any(),
                                any(),
                                any(),
                                any(),
                                isNull(),
                                isNull(),
                            )
                        }
                        And("Verify restoration events") {
                            val restoreEvents =
                                events.value.filterIsInstance<InternalSuperwallEvent.Restore>()
                            assert(restoreEvents.size == 2)
                            assert(restoreEvents[0].state is InternalSuperwallEvent.Restore.State.Start)
                            val failure: InternalSuperwallEvent.Restore.State.Failure =
                                restoreEvents[1].state as InternalSuperwallEvent.Restore.State.Failure
                            assert(failure.reason.contains("\"inactive\""))
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_with_free_trial_internal() =
        runTest {
            Given("We have loaded products with a free trial and we can purchase successfully") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                every { paywallView.encapsulatingActivity } returns WeakReference(mockk())
                every { playProduct.oneTimePurchaseOfferDetails } returns null
                every { playProduct.subscriptionOfferDetails } returns
                    listOf(
                        mockSubscriptionOfferDetails(
                            basePlanId = "basePlan",
                            pricingPhases = listOf(mockPricingPhase()),
                        ),
                        mockSubscriptionOfferDetails(
                            offerId = "offer1",
                            basePlanId = "basePlan",
                            pricingPhases = listOf(mockPricingPhase(0.0), mockPricingPhase()),
                        ),
                    )
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Purchased()
                When("We try to purchase a product with a free trial from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify free trial start event") {
                            val freeTrialStartEvent =
                                events.value.filterIsInstance<InternalSuperwallEvent.FreeTrialStart>()
                            assert(freeTrialStartEvent.isNotEmpty())
                            assert(freeTrialStartEvent.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_with_free_trial_external() =
        runTest {
            Given("We can purchase a product with a free trial externally") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                every { playProduct.oneTimePurchaseOfferDetails } returns null
                every { playProduct.subscriptionOfferDetails } returns
                    listOf(
                        mockSubscriptionOfferDetails(
                            basePlanId = "basePlan",
                            pricingPhases = listOf(mockPricingPhase()),
                        ),
                        mockSubscriptionOfferDetails(
                            offerId = "offer1",
                            basePlanId = "basePlan",
                            pricingPhases = listOf(mockPricingPhase(0.0), mockPricingPhase()),
                        ),
                    )
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase the product") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.ExternalPurchase(
                                StoreProduct(mockProduct),
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify free trial start event") {
                            val freeTrialStartEvent =
                                events.value.filterIsInstance<InternalSuperwallEvent.FreeTrialStart>()
                            assert(freeTrialStartEvent.isNotEmpty())
                            assert(freeTrialStartEvent.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_non_recurring_product_internal() =
        runTest {
            Given("We have loaded a non-recurring product and we can purchase successfully") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                every { playProduct.oneTimePurchaseOfferDetails } returns null
                every { playProduct.subscriptionOfferDetails } returns null
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase a non-recurring product from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify non-recurring product purchase event") {
                            val nonRecurringPurchaseEvent =
                                events.value.filterIsInstance<InternalSuperwallEvent.NonRecurringProductPurchase>()
                            assert(nonRecurringPurchaseEvent.isNotEmpty())
                            assert(nonRecurringPurchaseEvent.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_non_recurring_product_external() =
        runTest {
            Given("We can purchase a non-recurring product externally") {
                every { playProduct.oneTimePurchaseOfferDetails } returns null
                every { playProduct.subscriptionOfferDetails } returns null

                val nonRecurringStoreProduct = StoreProduct(mockProduct)

                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase the product") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.ExternalPurchase(nonRecurringStoreProduct),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify non-recurring product purchase event") {
                            val nonRecurringPurchaseEvent =
                                events.value.filterIsInstance<InternalSuperwallEvent.NonRecurringProductPurchase>()
                            assert(nonRecurringPurchaseEvent.isNotEmpty())
                            assert(nonRecurringPurchaseEvent.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_subscription_without_trial_internal() =
        runTest {
            Given("We have loaded a subscription product without trial and we can purchase successfully") {
                storeKitManager.getProducts(paywall = mockedPaywall)
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                every { playProduct.oneTimePurchaseOfferDetails } returns null
                every { playProduct.subscriptionOfferDetails } returns
                    listOf(
                        mockSubscriptionOfferDetails(
                            basePlanId = "basePlan",
                            pricingPhases = listOf(mockPricingPhase()),
                        ),
                    )
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase a subscription without trial from the paywall") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.Internal(
                                "product1",
                                paywallView,
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify subscription start event") {
                            val subscriptionStartEvent =
                                events.value.filterIsInstance<InternalSuperwallEvent.SubscriptionStart>()
                            assert(subscriptionStartEvent.isNotEmpty())
                            assert(subscriptionStartEvent.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }

    @Test
    fun test_purchase_subscription_without_trial_external() =
        runTest {
            Given("We can purchase a subscription product without trial externally") {
                val events = MutableStateFlow(emptyList<TrackableSuperwallEvent>())
                val transactionManager: TransactionManager =
                    manager(track = { e ->
                        events.update { it + e }
                    })
                every { playProduct.oneTimePurchaseOfferDetails } returns null
                every { playProduct.subscriptionOfferDetails } returns
                    listOf(
                        mockSubscriptionOfferDetails(
                            basePlanId = "basePlan",
                            pricingPhases = listOf(mockPricingPhase()),
                        ),
                    )
                coEvery {
                    purchaseController.purchase(
                        any(),
                        any(),
                        "basePlan",
                        any(),
                    )
                } returns PurchaseResult.Purchased()

                When("We try to purchase a subscription without trial externally") {
                    val result =
                        transactionManager.purchase(
                            TransactionManager.PurchaseSource.ExternalPurchase(
                                StoreProduct(mockProduct),
                            ),
                        )
                    Then("The purchase is successful") {
                        assert(result is PurchaseResult.Purchased)
                        coVerify { storeKitManager.loadPurchasedProducts() }
                        And("Verify subscription start event") {
                            val subscriptionStartEvent =
                                events.value.filterIsInstance<InternalSuperwallEvent.SubscriptionStart>()
                            assert(subscriptionStartEvent.isNotEmpty())
                            assert(subscriptionStartEvent.first().product?.fullIdentifier == "product1")
                        }
                    }
                }
            }
        }
}
