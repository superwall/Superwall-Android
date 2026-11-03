@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing.google

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.BillingConnection
import com.superwall.sdk.billing.ProductDetailsRepository
import com.superwall.sdk.billing.PurchaseQueries
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePlayStoreTest {
    private fun result(code: Int): BillingResult = BillingResult.newBuilder().setResponseCode(code).build()

    private fun purchase(token: String): Purchase =
        mockk {
            every { purchaseToken } returns token
            every { orderId } returns "order-$token"
            every { products } returns listOf("product-$token")
            every { purchaseTime } returns 1L
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { isAutoRenewing } returns false
            every { isAcknowledged } returns false
        }

    /** A connected client that answers purchase queries and acknowledgements straight away. */
    private class FakeClient(
        val subscriptions: List<Purchase> = emptyList(),
        val oneTime: List<Purchase> = emptyList(),
        var queryCode: Int = BillingClient.BillingResponseCode.OK,
        var acknowledgeCode: Int = BillingClient.BillingResponseCode.OK,
        var launchCode: Int = BillingClient.BillingResponseCode.OK,
    ) {
        var queries = 0

        private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

        val client: BillingClient =
            mockk(relaxed = true) {
                every { isReady } returns true
                every { connectionState } returns BillingClient.ConnectionState.CONNECTED
                every { queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
                    // QueryPurchasesParams hides its type, so the first query answers with every
                    // purchase and the second with none.
                    val purchases = if (queries++ == 0) subscriptions + oneTime else emptyList()
                    secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result(queryCode), purchases)
                }
                every { acknowledgePurchase(any(), any()) } answers {
                    secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(result(acknowledgeCode))
                }
                every { launchBillingFlow(any(), any()) } answers { result(launchCode) }
            }
    }

    private class Harness(
        val client: BillingClient,
        val store: GooglePlayStore,
        val updates: MutableSharedFlow<StorePurchaseResult>,
        val purchases: PlayPurchases,
    )

    private fun TestScope.store(fake: FakeClient): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val connection =
            BillingConnection(
                scope = CoroutineScope(dispatcher),
                createBillingClient = { fake.client },
                purchasesListener = mockk(relaxed = true),
                stateListener = mockk(relaxed = true),
            )
        connection.connect()
        connection.setupFinished(result(BillingClient.BillingResponseCode.OK))
        runCurrent()
        val updates = MutableSharedFlow<StorePurchaseResult>(extraBufferCapacity = 8)
        val purchases = PlayPurchases()
        val store =
            GooglePlayStore(
                queries = PurchaseQueries(connection, ProductDetailsRepository(), appInBackground = { false }),
                availability = connection.availability,
                purchaseUpdates = updates,
                purchases = purchases,
                mainDispatcher = dispatcher,
                flowParams = { _, _ -> mockk(relaxed = true) },
            )
        return Harness(fake.client, store, updates, purchases)
    }

    private fun playProduct(): StoreProduct {
        val raw =
            mockk<RawStoreProduct>(relaxed = true) {
                every { underlyingProductDetails } returns mockk<ProductDetails>(relaxed = true)
                every { selectedOffer } returns null
                every { fullIdentifier } returns "monthly"
            }
        return StoreProduct(raw)
    }

    @Test
    fun `active purchases are both product types in neutral form, with the originals kept by token`() =
        runTest {
            Given("one subscription and one one-time purchase") {
                val sub = purchase("sub")
                val harness = store(FakeClient(subscriptions = listOf(sub), oneTime = listOf(purchase("otp"))))

                When("the active purchases are asked for") {
                    val purchases = harness.store.activePurchases().getOrThrow()

                    Then("both product types are queried and every purchase is returned") {
                        verify(exactly = 2) { harness.client.queryPurchasesAsync(any<QueryPurchasesParams>(), any()) }
                        assertEquals(setOf("sub", "otp"), purchases.map { it.token }.toSet())
                    }
                    And("the original Play purchase is kept for each token") {
                        assertSame(sub, harness.purchases.native("sub"))
                    }
                }
            }
        }

    @Test
    fun `active purchases fail when Play can't be asked instead of counting as none`() =
        runTest {
            Given("a client whose purchase queries fail") {
                val harness = store(FakeClient(queryCode = BillingClient.BillingResponseCode.ERROR))

                When("the active purchases are asked for") {
                    val result = backgroundScope.async { harness.store.activePurchases() }
                    advanceUntilIdle()

                    Then("the request fails") {
                        assertTrue(result.await().isFailure)
                    }
                }
            }
        }

    @Test
    fun `acknowledge reports Play's answer`() =
        runTest {
            Given("a client that acknowledges, then one that refuses") {
                val fake = FakeClient()
                val harness = store(fake)
                val purchase = purchase("t").toStorePurchase()

                Then("an OK acknowledgement succeeds and an error fails") {
                    assertTrue(harness.store.acknowledge(purchase).isSuccess)
                    fake.acknowledgeCode = BillingClient.BillingResponseCode.ERROR
                    assertTrue(harness.store.acknowledge(purchase).isFailure)
                }
            }
        }

    @Test
    fun `purchase launches Play's flow and returns the outcome its listener reports`() =
        runTest {
            Given("a connected store") {
                val fake = FakeClient()
                val harness = store(fake)

                When("a product is bought and Play reports it purchased") {
                    val outcome = backgroundScope.async { harness.store.purchase(mockk<Activity>(), playProduct()) }
                    runCurrent()
                    harness.updates.emit(StorePurchaseResult.Purchased(listOf(purchase("new").toStorePurchase())))
                    runCurrent()

                    Then("Play's flow was launched once") {
                        verify(exactly = 1) { fake.client.launchBillingFlow(any(), any()) }
                    }
                    And("the purchase returns what the listener reported") {
                        val purchased = outcome.await() as StorePurchaseResult.Purchased
                        assertEquals(listOf("new"), purchased.purchases.map { it.token })
                    }
                }
            }
        }

    @Test
    fun `purchase fails without waiting when Play's flow can't open`() =
        runTest {
            Given("a client that refuses to launch the flow") {
                val harness = store(FakeClient(launchCode = BillingClient.BillingResponseCode.ITEM_UNAVAILABLE))

                When("a product is bought") {
                    val outcome = harness.store.purchase(mockk<Activity>(), playProduct())

                    Then("the purchase fails with the response code") {
                        val failed = outcome as StorePurchaseResult.Failed
                        assertEquals("${BillingClient.BillingResponseCode.ITEM_UNAVAILABLE}", failed.error.message)
                    }
                }
            }
        }

    @Test
    fun `external purchases emit each purchase Play reports`() =
        runTest {
            Given("a store listening for purchases") {
                val harness = store(FakeClient())
                val first = backgroundScope.async { harness.store.externalPurchases.first() }
                runCurrent()

                When("Play reports a cancelled flow and then a purchase") {
                    harness.updates.emit(StorePurchaseResult.Cancelled)
                    harness.updates.emit(StorePurchaseResult.Purchased(listOf(purchase("late").toStorePurchase())))
                    runCurrent()

                    Then("only the purchase is emitted") {
                        assertEquals("late", first.await().token)
                    }
                }
            }
        }
}
