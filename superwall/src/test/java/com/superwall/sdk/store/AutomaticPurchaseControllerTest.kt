@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticPurchaseControllerTest {
    @Before
    fun setUp() {
        // Lets a sync run to completion without a configured SDK
        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns
            mockk(relaxed = true) {
                every { configurationStateListener } returns MutableStateFlow(ConfigurationStatus.Configured)
            }
    }

    @After
    fun tearDown() {
        unmockkObject(Superwall.Companion)
    }

    private val entitlements =
        mockk<Entitlements>(relaxed = true) {
            every { status } returns MutableStateFlow(SubscriptionStatus.Unknown)
            every { web } returns emptySet()
        }

    private fun TestScope.makeController(getBilling: () -> BillingClient): AutomaticPurchaseController =
        AutomaticPurchaseController(
            context = mockk(relaxed = true),
            scope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            entitlementsInfo = { entitlements },
            deviceEntitlementRecords = { emptySet() },
            getBilling = { _, _ -> getBilling() },
        )

    private fun billingResult(code: Int): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage("")
            .build()

    private fun purchase(
        token: String,
        state: Int = Purchase.PurchaseState.PURCHASED,
        acknowledged: Boolean = false,
    ): Purchase =
        mockk(relaxed = true) {
            every { purchaseToken } returns token
            every { purchaseState } returns state
            every { isAcknowledged } returns acknowledged
            every { products } returns listOf("product_$token")
        }

    /**
     * A connected client whose purchase queries return [purchases]
     * and whose acknowledgements answer with the codes in [acknowledgeCodes] in order,
     * repeating the last one. Acknowledged tokens are recorded in [acknowledged].
     */
    private fun connectedClient(
        purchases: List<Purchase>,
        acknowledgeCodes: List<Int> = listOf(BillingClient.BillingResponseCode.OK),
        acknowledged: MutableList<String> = mutableListOf(),
    ): BillingClient {
        var acknowledgeCalls = 0
        return mockk(relaxed = true) {
            every { isReady } returns true
            every { startConnection(any()) } answers {
                firstArg<BillingClientStateListener>().onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
            }
            every { queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } answers {
                secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(
                    billingResult(BillingClient.BillingResponseCode.OK),
                    purchases,
                )
            }
            val ackParams = slot<AcknowledgePurchaseParams>()
            every { acknowledgePurchase(capture(ackParams), any()) } answers {
                val code = acknowledgeCodes[minOf(acknowledgeCalls++, acknowledgeCodes.lastIndex)]
                if (code == BillingClient.BillingResponseCode.OK) acknowledged += ackParams.captured.purchaseToken
                secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(billingResult(code))
            }
        }
    }

    private fun clientFinishingSetupWith(code: Int): BillingClient =
        mockk(relaxed = true) {
            every { isReady } returns false
            every { startConnection(any()) } answers {
                firstArg<BillingClientStateListener>().onBillingSetupFinished(
                    BillingResult
                        .newBuilder()
                        .setResponseCode(code)
                        .setDebugMessage("")
                        .build(),
                )
            }
        }

    @Test
    fun `purchase fails instead of throwing or hanging when the billing client can't be created`() =
        runTest {
            Given("a device where creating the billing client throws") {
                val controller = makeController { throw IllegalStateException("No Play Store") }
                advanceUntilIdle()

                When("a purchase is attempted") {
                    @Suppress("DEPRECATION")
                    val result = controller.purchase(mockk<Activity>(relaxed = true), mockk<ProductDetails>(relaxed = true), null, null)

                    Then("it fails") {
                        assertTrue(result is PurchaseResult.Failed)
                    }
                }
            }
        }

    @Test
    fun `purchase fails after one more connection attempt when billing is unavailable`() =
        runTest {
            Given("a device where billing setup reports BILLING_UNAVAILABLE") {
                val client = clientFinishingSetupWith(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
                val controller = makeController { client }
                advanceUntilIdle()

                When("a purchase is attempted") {
                    @Suppress("DEPRECATION")
                    val result = controller.purchase(mockk<Activity>(relaxed = true), mockk<ProductDetails>(relaxed = true), null, null)

                    Then("it fails") {
                        assertTrue(result is PurchaseResult.Failed)
                    }

                    And("the connection was retried once for the purchase") {
                        verify(exactly = 2) { client.startConnection(any()) }
                        assertEquals(0L, testScheduler.currentTime)
                    }
                }
            }
        }

    @Test
    fun `sync acknowledges purchases that were never acknowledged`() =
        runTest {
            Given("a purchase that completed outside the billing flow callback") {
                val acknowledged = mutableListOf<String>()
                val client =
                    connectedClient(
                        purchases =
                            listOf(
                                purchase("unacknowledged"),
                                purchase("already", acknowledged = true),
                                purchase("pending", state = Purchase.PurchaseState.PENDING),
                            ),
                        acknowledged = acknowledged,
                    )
                val controller = makeController { client }
                advanceUntilIdle()

                When("the subscription status is synced") {
                    controller.restorePurchases()
                    advanceUntilIdle()

                    Then("only the purchased, unacknowledged purchase is acknowledged") {
                        assertEquals(listOf("unacknowledged"), acknowledged)
                    }
                }
            }
        }

    @Test
    fun `failed acknowledgements are retried`() =
        runTest {
            Given("a purchase whose first acknowledgement fails") {
                val acknowledged = mutableListOf<String>()
                val client =
                    connectedClient(
                        purchases = listOf(purchase("token")),
                        acknowledgeCodes =
                            listOf(
                                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                                BillingClient.BillingResponseCode.OK,
                            ),
                        acknowledged = acknowledged,
                    )
                val controller = makeController { client }
                advanceUntilIdle()

                When("the subscription status is synced") {
                    controller.restorePurchases()
                    advanceUntilIdle()

                    Then("the acknowledgement is retried until it succeeds") {
                        verify(exactly = 2) { client.acknowledgePurchase(any(), any()) }
                        assertEquals(listOf("token"), acknowledged)
                    }
                }
            }
        }

    @Test
    fun `a purchase seen by the flow callback and the sync is acknowledged once`() =
        runTest {
            Given("an acknowledgement that has not answered yet") {
                val pending = mutableListOf<AcknowledgePurchaseResponseListener>()
                val purchase = purchase("token")
                val client =
                    connectedClient(purchases = listOf(purchase)).also {
                        every { it.acknowledgePurchase(any(), any()) } answers { pending += secondArg<AcknowledgePurchaseResponseListener>() }
                    }
                val controller = makeController { client }
                advanceUntilIdle()

                When("the billing flow reports the purchase and the sync sees it too") {
                    controller.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), mutableListOf(purchase))
                    controller.restorePurchases()

                    Then("it is acknowledged once") {
                        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
                    }
                }
                pending.forEach { it.onAcknowledgePurchaseResponse(billingResult(BillingClient.BillingResponseCode.OK)) }
            }
        }
}
