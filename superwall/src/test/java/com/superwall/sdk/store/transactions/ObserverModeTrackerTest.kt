package com.superwall.sdk.store.transactions

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.google.toStorePurchase
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObserverModeTrackerTest {
    private val settled = mutableListOf<Triple<String, PurchaseSource, PurchaseOutcome>>()
    private val restored = mutableListOf<String>()
    private var receiptsRefreshed = 0
    private val purchaseResults = MutableStateFlow<InternalPurchaseResult?>(null)
    private val products = mutableMapOf<String, StoreProduct>()
    private var observePurchases = false
    private var sdkPurchasing = false

    private val storeManager =
        mockk<StoreManager>(relaxed = true) {
            every { billing.purchaseResults } returns purchaseResults
            every { getProductFromCache(any()) } answers { products[firstArg()] }
        }

    private fun tracker() =
        ObserverModeTracker(
            dependencies =
                TransactionDependencies(
                    storeManager = storeManager,
                    purchaseController = mockk(relaxed = true),
                    eventsQueue = mockk(relaxed = true),
                    storage = mockk(relaxed = true),
                    activityProvider = mockk(relaxed = true),
                    factory =
                        mockk(relaxed = true) {
                            every { makeSuperwallOptions().shouldObservePurchases } answers { observePurchases }
                        },
                    ioScope = IOScope(Dispatchers.Unconfined),
                    track = {},
                    dismiss = { _, _ -> },
                    showAlert = {},
                    subscriptionStatus = { mockk() },
                    entitlementsById = { emptySet() },
                    allEntitlementsByProductId = { emptyMap() },
                    webEntitlements = { emptySet() },
                    currentCustomerInfo = { mockk() },
                    showRestoreDialogForWeb = {},
                    notifyBackendOfReceipts = {},
                    refreshReceipt = { receiptsRefreshed++ },
                    updateState = { _, _ -> },
                    notifyOfTransactionComplete = { _, _, _, _ -> },
                    notifyOfTransactionAbandon = {},
                    testMode = null,
                    testModeTransactionHandler = null,
                    setSubscriptionStatus = null,
                    activeDeviceEntitlements = { emptySet() },
                    openRestoreOnWeb = {},
                ),
            settle = { product, source, outcome -> settled += Triple(product.fullIdentifier, source, outcome) },
            restored = { product, _ -> restored += product.fullIdentifier },
            sdkPurchaseInFlight = { sdkPurchasing },
        )

    private val raw = mockk<RawStoreProduct>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(RawStoreProduct.Companion)
        every { RawStoreProduct.from(any()) } returns raw
        every { raw.fullIdentifier } returns "from-details"
    }

    @After
    fun tearDown() = unmockkObject(RawStoreProduct.Companion)

    private fun details(id: String) = mockk<ProductDetails>(relaxed = true) { every { productId } returns id }

    private fun product(id: String) =
        mockk<StoreProduct>(relaxed = true) {
            every { fullIdentifier } returns id
            every { hasFreeTrial } returns false
        }.also { products[id] = it }

    private fun purchaseOf(vararg ids: String) = mockk<Purchase>(relaxed = true) { every { this@mockk.products } returns ids.toList() }

    private fun okResult(vararg purchases: Purchase) =
        PurchasingObserverState.PurchaseResult(
            BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
            purchases.toList(),
        )

    @Test
    fun `a purchased result settles each cached product and refreshes the receipt`() =
        runTest {
            Given("an observed purchase of p1") {
                val tracker = tracker()
                tracker.begin(ObservedPurchase("p1", details("p1")))
                product("p1")
                val purchase = purchaseOf("p1")

                When("Play reports it purchased") {
                    tracker.handle(InternalPurchaseResult.Purchased(purchase), okResult(purchase))

                    Then("the receipt is refreshed, p1 settles as purchased and nothing is observed") {
                        assertEquals(1, receiptsRefreshed)
                        assertEquals(1, settled.size)
                        val (id, source, outcome) = settled.single()
                        assertEquals("p1", id)
                        assertTrue(source is PurchaseSource.ObserverMode)
                        assertEquals(PurchaseOutcome.Purchased(false, purchase.toStorePurchase()), outcome)
                        assertTrue(tracker.state.value.observed.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `a cancel resolves the latest observed purchase only`() =
        runTest {
            Given("two observed purchases, p2 after p1") {
                val tracker = tracker()
                tracker.begin(ObservedPurchase("p1", details("p1")))
                tracker.begin(ObservedPurchase("p2", details("p2")))

                When("Play reports a cancel") {
                    tracker.handle(InternalPurchaseResult.Cancelled, okResult())

                    Then("it settles as cancelled and p1 stays observed") {
                        assertEquals(PurchaseOutcome.Cancelled, settled.single().third)
                        assertEquals(listOf("p1"), tracker.state.value.observed.map { it.productId })
                    }
                }
            }
        }

    @Test
    fun `a failure settles with the play error message`() =
        runTest {
            Given("an observed purchase that fails with an unknown error") {
                val tracker = tracker()
                val d = details("p1")
                tracker.begin(ObservedPurchase("p1", d))
                val error = Throwable("boom")

                When("Play reports the failure") {
                    tracker.handle(InternalPurchaseResult.Failed(error), PurchasingObserverState.PurchaseError(d, error))

                    Then("it settles as failed with the fallback message") {
                        assertEquals(PurchaseOutcome.Failed("Unknown error"), settled.single().third)
                    }
                }
            }
        }

    @Test
    fun `pending and restored results go to the purchase and restore flows`() =
        runTest {
            Given("two observed purchases") {
                val tracker = tracker()
                tracker.begin(ObservedPurchase("p1", details("p1")))
                tracker.begin(ObservedPurchase("p2", details("p2")))
                product("p1")
                product("p2")

                When("one turns pending and the other restores") {
                    tracker.handle(InternalPurchaseResult.Pending, okResult(purchaseOf("p2")))
                    tracker.handle(InternalPurchaseResult.Restored, okResult(purchaseOf("p1")))

                    Then("p2 settles as pending, p1 is restored and nothing is observed") {
                        assertEquals(PurchaseOutcome.Pending, settled.single().third)
                        assertEquals(listOf("p1"), restored)
                        assertTrue(tracker.state.value.observed.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `a result with nothing observed is ignored`() =
        runTest {
            Given("no observed purchase") {
                val tracker = tracker()

                When("a purchased result arrives") {
                    tracker.handle(InternalPurchaseResult.Purchased(purchaseOf("p1")), okResult(purchaseOf("p1")))

                    Then("nothing is settled and the receipt is not refreshed") {
                        assertTrue(settled.isEmpty())
                        assertEquals(0, receiptsRefreshed)
                    }
                }
            }
        }

    @Test
    fun `with observing on, a billing cancel settles only the latest observed purchase`() =
        runTest {
            Given("observing is on and two purchases are observed") {
                observePurchases = true
                val tracker = tracker()
                tracker.start()
                tracker.begin(ObservedPurchase("p1", details("p1")))
                tracker.begin(ObservedPurchase("p2", details("p2")))

                When("Play's listener reports a cancel") {
                    purchaseResults.value = InternalPurchaseResult.Cancelled

                    Then("p2 settles as cancelled and p1 is still observed") {
                        assertEquals(PurchaseOutcome.Cancelled, settled.single().third)
                        assertEquals(listOf("p1"), tracker.state.value.observed.map { it.productId })
                    }
                }
            }
        }

    @Test
    fun `with observing on, a lone observed purchase reported by the listener is tracked`() =
        runTest {
            Given("observing is on and one subscription with a base plan is observed") {
                observePurchases = true
                val tracker = tracker()
                tracker.start()
                tracker.begin(ObservedPurchase("p1:base:offer", details("p1")))
                product("p1")
                val purchase = purchaseOf("p1")

                When("Play's listener reports it purchased") {
                    purchaseResults.value = InternalPurchaseResult.Purchased(purchase)

                    Then("it settles as purchased once and nothing is observed") {
                        assertEquals(1, receiptsRefreshed)
                        assertEquals(PurchaseOutcome.Purchased(false, purchase.toStorePurchase()), settled.single().third)
                        assertTrue(tracker.state.value.observed.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `with observing on, a lone observed cancel reported by the listener is tracked`() =
        runTest {
            Given("observing is on and one purchase is observed") {
                observePurchases = true
                val tracker = tracker()
                tracker.start()
                tracker.begin(ObservedPurchase("p1", details("p1")))

                When("Play's listener reports a cancel") {
                    purchaseResults.value = InternalPurchaseResult.Cancelled

                    Then("it settles as cancelled") {
                        assertEquals(PurchaseOutcome.Cancelled, settled.single().third)
                        assertTrue(tracker.state.value.observed.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `with observing on, the listener leaves the sdk's own purchase to the purchase flow`() =
        runTest {
            Given("observing is on, p1 is observed and the SDK runs its own purchase") {
                observePurchases = true
                sdkPurchasing = true
                val tracker = tracker()
                tracker.start()
                tracker.begin(ObservedPurchase("p1", details("p1")))

                When("Play's listener reports a cancel") {
                    purchaseResults.value = InternalPurchaseResult.Cancelled

                    Then("nothing is settled and p1 is still observed") {
                        assertTrue(settled.isEmpty())
                        assertEquals(1, tracker.state.value.observed.size)
                    }
                }
            }
        }

    @Test
    fun `with observing on, a purchase of a product nobody observes is ignored`() =
        runTest {
            Given("observing is on and p1 is observed") {
                observePurchases = true
                val tracker = tracker()
                tracker.start()
                tracker.begin(ObservedPurchase("p1", details("p1")))
                product("p2")

                When("Play's listener reports a purchase of p2") {
                    purchaseResults.value = InternalPurchaseResult.Purchased(purchaseOf("p2"))

                    Then("nothing is settled and p1 is still observed") {
                        assertTrue(settled.isEmpty())
                        assertEquals(1, tracker.state.value.observed.size)
                    }
                }
            }
        }

    @Test
    fun `results with a mismatched observer state settle without crashing`() =
        runTest {
            Given("two observed purchases") {
                val tracker = tracker()
                val d1 = details("p1")
                tracker.begin(ObservedPurchase("p1", d1))
                tracker.begin(ObservedPurchase("p2", details("p2")))

                When("a failure arrives with a result state and a pending result with an error state") {
                    tracker.handle(InternalPurchaseResult.Failed(Throwable("boom")), okResult())
                    tracker.handle(InternalPurchaseResult.Pending, PurchasingObserverState.PurchaseError(d1, Throwable("x")))

                    Then("the failure settles and the pending result has no purchases to settle") {
                        assertEquals(PurchaseOutcome.Failed("Unknown error"), settled.single().third)
                        assertTrue(tracker.state.value.observed.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `with observing off, billing results are not followed`() =
        runTest {
            Given("observing is off and a purchase is observed") {
                val tracker = tracker()
                tracker.start()
                tracker.begin(ObservedPurchase("p1", details("p1")))

                When("Play's listener reports a cancel") {
                    purchaseResults.value = InternalPurchaseResult.Cancelled

                    Then("nothing is settled") {
                        assertTrue(settled.isEmpty())
                        assertEquals(1, tracker.state.value.observed.size)
                    }
                }
            }
        }

    @Test
    fun `reducers keep order and replace a re-observed product`() {
        Given("observed p1 then p2") {
            val d1 = details("p1")
            val d2 = details("p2")
            val state =
                ObserverModeState.Updates
                    .Begin(ObservedPurchase("p2", d2))
                    .reduce(ObserverModeState.Updates.Begin(ObservedPurchase("p1", d1)).reduce(ObserverModeState()))

            Then("p2 is latest; observing p1 again makes it latest; Remove shrinks the list") {
                assertEquals("p2", state.latest?.productId)
                val again = ObserverModeState.Updates.Begin(ObservedPurchase("p1", d1)).reduce(state)
                assertEquals(listOf("p2", "p1"), again.observed.map { it.productId })
                assertEquals(listOf("p1"), ObserverModeState.Updates.Remove(listOf("p2")).reduce(again).observed.map { it.productId })
            }
        }
    }
}
