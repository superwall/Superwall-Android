package com.superwall.sdk.store.transactions

import android.app.Activity
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.storage.PurchasingProductdIds
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseFlowTest {
    private val storage = mockk<Storage>(relaxed = true)
    private val tracked = mutableListOf<TrackableSuperwallEvent>()
    private val observed = mutableListOf<ObservedPurchase>()
    private val gate = CompletableDeferred<PurchaseResult>()
    private val product = product("com.app.monthly")

    private val storeManager =
        mockk<StoreManager>(relaxed = true) {
            every { billing } returns mockk(relaxed = true) { every { purchaseResults } returns MutableStateFlow(null) }
            every { getProductFromCache("com.app.monthly") } returns product
            every { getProductFromCache("missing") } returns null
            coEvery { purchaseController.purchase(any(), any<StoreProduct>(), any(), any(), any()) } coAnswers { gate.await() }
        }

    private val flow =
        PurchaseFlow(
            dependencies =
                TransactionDependencies(
                    storeManager = storeManager,
                    purchaseController = mockk(relaxed = true),
                    eventsQueue = mockk(relaxed = true),
                    storage = storage,
                    activityProvider = mockk { every { getCurrentActivity() } returns mockk<Activity>() },
                    factory = mockk(relaxed = true),
                    ioScope = IOScope(Dispatchers.Unconfined),
                    track = { tracked += it },
                    dismiss = { _, _ -> },
                    showAlert = {},
                    subscriptionStatus = { mockk() },
                    entitlementsById = { emptySet() },
                    allEntitlementsByProductId = { emptyMap() },
                    webEntitlements = { emptySet() },
                    currentCustomerInfo = { mockk() },
                    showRestoreDialogForWeb = {},
                    notifyBackendOfReceipts = {},
                    refreshReceipt = {},
                    updateState = { _, _ -> },
                    notifyOfTransactionComplete = { _, _, _, _ -> },
                    notifyOfTransactionAbandon = {},
                    testMode = null,
                    testModeTransactionHandler = null,
                    setSubscriptionStatus = null,
                    activeDeviceEntitlements = { emptySet() },
                    openRestoreOnWeb = {},
                ),
            onObservedPurchase = { observed += it },
        )

    private fun product(id: String) =
        mockk<StoreProduct>(relaxed = true) {
            every { fullIdentifier } returns id
            every { isCustomProduct } returns false
            every { hasFreeTrial } returns false
            every { rawStoreProduct } returns
                mockk<RawStoreProduct>(relaxed = true) {
                    every { selectedOffer } returns null
                    every { offerId } returns null
                    every { basePlanId } returns null
                }
        }

    private fun paywall(productId: String = "com.app.monthly") =
        PurchaseSource.Internal(productId, mockk<PaywallViewState>(relaxed = true))

    @Test
    fun `a second paywall purchase is turned away while the first runs`() =
        runTest {
            Given("a paywall purchase waiting on the store") {
                var admissions = 0
                val first = async(Dispatchers.Unconfined) { flow.purchase(paywall(), true) { admissions++ } }

                When("the paywall asks to purchase again") {
                    val second = flow.purchase(paywall(), true) { admissions++ }

                    Then("the second is refused without reaching the store or the paywall's loading state") {
                        assertEquals(PurchaseResult.Failed(PurchaseFlow.ALREADY_PURCHASING), second)
                        assertEquals(1, admissions)
                        coVerify(exactly = 1) { storeManager.purchaseController.purchase(any(), any<StoreProduct>(), any(), any(), any()) }
                        assertTrue(flow.state.value.isPurchasingFromPaywall)
                    }

                    And("the first purchase ends") {
                        gate.complete(PurchaseResult.Cancelled())
                        first.await()

                        Then("nothing is in flight and the next paywall purchase is admitted") {
                            assertTrue(flow.state.value.inFlight.isEmpty())
                            flow.purchase(paywall(), true) { admissions++ }
                            assertEquals(2, admissions)
                        }
                    }
                }
            }
        }

    @Test
    fun `an external purchase is not turned away by a running paywall purchase`() =
        runTest {
            Given("a paywall purchase waiting on the store") {
                val first = async(Dispatchers.Unconfined) { flow.purchase(paywall(), true) }

                When("the app purchases through the SDK at the same time") {
                    val second = async(Dispatchers.Unconfined) { flow.purchase(PurchaseSource.ExternalPurchase(product), true) }

                    Then("both reach the store") {
                        coVerify(exactly = 2) { storeManager.purchaseController.purchase(any(), any<StoreProduct>(), any(), any(), any()) }
                        assertEquals(2, flow.state.value.inFlight.size)
                    }
                    gate.complete(PurchaseResult.Cancelled())
                    first.await()
                    second.await()
                }
            }
        }

    @Test
    fun `a purchase that throws is still released`() =
        runTest {
            Given("a store that throws") {
                coEvery {
                    storeManager.purchaseController.purchase(any(), any<StoreProduct>(), any(), any(), any())
                } throws IllegalStateException("boom")

                When("a paywall purchase runs") {
                    val error = runCatching { flow.purchase(paywall(), true) }.exceptionOrNull()

                    Then("the error reaches the caller and nothing stays in flight") {
                        assertEquals("boom", error?.message)
                        assertTrue(flow.state.value.inFlight.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `a paywall product that never loaded fails without reserving a purchase`() =
        runTest {
            Given("a paywall whose product is not cached") {
                var admitted = false

                When("it asks to purchase") {
                    val result = flow.purchase(paywall("missing"), true) { admitted = true }

                    Then("it fails as before, after showing the loading state, and nothing is in flight") {
                        assertEquals(PurchaseResult.Failed("Product not found"), result)
                        assertTrue(admitted)
                        assertTrue(flow.state.value.inFlight.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `a pending purchase stays as pending and tracks the pending event`() =
        runTest {
            Given("a store that holds the purchase for approval") {
                gate.complete(PurchaseResult.Pending())

                When("a paywall purchase runs") {
                    val result = flow.purchase(paywall(), true)

                    Then("it returns pending, keeps the attempt as pending and tracks it") {
                        assertEquals(PurchaseResult.Pending(), result)
                        val entry = flow.state.value.inFlight.values.single()
                        assertTrue(entry is InFlightPurchase.Pending)
                        assertTrue(tracked.any { it is InternalSuperwallEvent.Transaction && it.state is InternalSuperwallEvent.Transaction.State.Fail })
                    }
                }
            }
        }

    @Test
    fun `an observed purchase start is handed to the observer-mode tracker`() =
        runTest {
            Given("a purchase the app started itself in observer mode") {
                When("its start is prepared") {
                    flow.prepareToPurchase(product, PurchaseSource.ObserverMode(product))

                    Then("the tracker learns about it") {
                        assertEquals(listOf("com.app.monthly"), observed.map { it.productId })
                    }
                }
            }
        }

    @Test
    fun `clearing purchasing ids persists what remains`() =
        runTest {
            Given("stored purchasing ids") {
                every { storage.read(PurchasingProductdIds) } returns setOf("a", "b", "c")

                When("two finished purchases are cleared") {
                    flow.clearPurchasingIds(listOf("a", "c"))

                    Then("the rest is persisted and kept in state") {
                        verify { storage.write(PurchasingProductdIds, setOf("b")) }
                        assertEquals(setOf("b"), flow.state.value.purchasingProductIds)
                    }
                }
            }
        }
}
