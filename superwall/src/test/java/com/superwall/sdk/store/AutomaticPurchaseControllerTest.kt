@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.PurchaseFlowResult
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun result(code: Int): BillingResult =
    BillingResult
        .newBuilder()
        .setResponseCode(code)
        .setDebugMessage("")
        .build()

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AutomaticPurchaseControllerTest {
    @Before
    fun setUp() {
        // Lets a sync run to completion without a configured SDK
        mockkObject(Superwall.Companion)
        every { Superwall.hasInitialized } returns flowOf(true)
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

    /**
     * A [Billing] whose purchase queries return [purchases], whose acknowledgements answer with
     * [acknowledgeCodes] in order (repeating the last one), and whose billing flow answers with
     * [flowResult]. Acknowledged tokens are recorded in [acknowledged].
     */
    private class FakeBilling(
        var purchases: List<Purchase> = emptyList(),
        val acknowledgeCodes: List<Int> = listOf(BillingClient.BillingResponseCode.OK),
        var flowResult: PurchaseFlowResult? = null,
        var connected: Boolean = true,
    ) : Billing {
        val acknowledged = mutableListOf<String>()
        var acknowledgeCalls = 0
        var flowLaunches = 0
        var purchaseQueries = 0
        var pendingAcknowledgement: CompletableDeferred<Result<Unit>>? = null

        override val purchaseResults = MutableStateFlow<InternalPurchaseResult?>(null)

        override suspend fun queryPurchasesOfType(productType: String): Result<List<Purchase>> {
            purchaseQueries++
            if (!connected) return Result.failure(IllegalStateException("not connected"))
            val type = productType
            return Result.success(purchases.filter { (it.products.first().startsWith("sub_")) == (type == BillingClient.ProductType.SUBS) })
        }

        override suspend fun launchBillingFlow(
            activity: Activity,
            params: BillingFlowParams,
        ): PurchaseFlowResult {
            flowLaunches++
            return flowResult ?: PurchaseFlowResult(result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
        }

        override suspend fun acknowledge(purchaseToken: String): Result<Unit> {
            pendingAcknowledgement?.let { return it.await() }
            val code = acknowledgeCodes[minOf(acknowledgeCalls++, acknowledgeCodes.lastIndex)]
            return if (code == BillingClient.BillingResponseCode.OK) {
                acknowledged += purchaseToken
                Result.success(Unit)
            } else {
                Result.failure(Throwable("ack failed $code"))
            }
        }

        override suspend fun awaitGetProducts(identifiers: Set<String>): Set<StoreProduct> = emptySet()

        override suspend fun getLatestTransaction(factory: StoreTransactionFactory): StoreTransaction? = null

        override suspend fun queryAllPurchases(): List<Purchase> = purchases

        override suspend fun querySubscriptionPurchases(): Result<List<Purchase>> = Result.success(purchases)

        override suspend fun consume(purchaseToken: String): Result<String> = Result.success(purchaseToken)

        override suspend fun getStorefrontCountryCode(): String? = null
    }

    private fun TestScope.makeController(billing: Billing): AutomaticPurchaseController =
        AutomaticPurchaseController(
            scope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            billing = billing,
            entitlementsInfo = { entitlements },
            deviceEntitlementRecords = { emptySet() },
        )

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

    private fun productDetails(): ProductDetails =
        mockk(relaxed = true) {
            every { productId } returns "product"
            every { productType } returns BillingClient.ProductType.INAPP
            every { oneTimePurchaseOfferDetails } returns null
            every { oneTimePurchaseOfferDetailsList } returns null
            every { subscriptionOfferDetails } returns null
        }

    @Test
    fun `purchase fails instead of hanging when billing is unavailable`() =
        runTest {
            Given("billing that can't connect") {
                val billing = FakeBilling(connected = false)
                val controller = makeController(billing)
                advanceUntilIdle()

                When("a purchase is attempted") {
                    @Suppress("DEPRECATION")
                    val result = controller.purchase(mockk<Activity>(relaxed = true), productDetails(), null, null)

                    Then("it fails with the billing unavailable message") {
                        assertTrue(result is PurchaseResult.Failed)
                        assertEquals(
                            "Google Play Billing is not available on this device.",
                            (result as PurchaseResult.Failed).errorMessage,
                        )
                    }
                }
            }
        }

    @Test
    fun `purchase returns the result of its own billing flow, with the purchase`() =
        runTest {
            Given("billing whose flow completes with a purchase") {
                val bought = purchase("bought")
                val billing =
                    FakeBilling(
                        flowResult = PurchaseFlowResult(result(BillingClient.BillingResponseCode.OK), listOf(bought)),
                    )
                val controller = makeController(billing)
                advanceUntilIdle()

                When("a purchase is made") {
                    @Suppress("DEPRECATION")
                    val result = controller.purchase(mockk<Activity>(relaxed = true), productDetails(), null, null)

                    Then("it is purchased and carries the Play purchase") {
                        assertTrue(result is PurchaseResult.Purchased)
                        assertEquals(listOf(bought), (result as PurchaseResult.Purchased).purchases)
                    }
                    And("the flow went through the shared billing connection") {
                        assertEquals(1, billing.flowLaunches)
                    }
                }
            }
        }

    @Test
    fun `purchase maps a cancelled flow to Cancelled`() =
        runTest {
            Given("billing whose flow is cancelled") {
                val billing =
                    FakeBilling(flowResult = PurchaseFlowResult(result(BillingClient.BillingResponseCode.USER_CANCELED)))
                val controller = makeController(billing)

                Then("the purchase is cancelled") {
                    @Suppress("DEPRECATION")
                    val outcome = controller.purchase(mockk<Activity>(relaxed = true), productDetails(), null, null)
                    assertTrue(outcome is PurchaseResult.Cancelled)
                }
            }
        }

    @Test
    fun `sync acknowledges purchases that were never acknowledged`() =
        runTest {
            Given("a purchase that completed outside the billing flow callback") {
                val billing =
                    FakeBilling(
                        purchases =
                            listOf(
                                purchase("unacknowledged"),
                                purchase("already", acknowledged = true),
                                purchase("pending", state = Purchase.PurchaseState.PENDING),
                            ),
                    )
                val controller = makeController(billing)
                advanceUntilIdle()
                billing.acknowledged.clear()

                When("the subscription status is synced") {
                    controller.restorePurchases()
                    advanceUntilIdle()

                    Then("only the purchased, unacknowledged purchase is acknowledged") {
                        assertEquals(listOf("unacknowledged"), billing.acknowledged)
                    }
                }
            }
        }

    @Test
    fun `failed acknowledgements are retried`() =
        runTest {
            Given("a purchase whose first acknowledgement fails") {
                val billing =
                    FakeBilling(
                        purchases = listOf(purchase("token")),
                        acknowledgeCodes =
                            listOf(
                                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                                BillingClient.BillingResponseCode.OK,
                            ),
                    )
                When("the startup sync runs") {
                    makeController(billing)
                    advanceUntilIdle()

                    Then("the acknowledgement is retried until it succeeds") {
                        assertEquals(2, billing.acknowledgeCalls)
                        assertEquals(listOf("token"), billing.acknowledged)
                    }
                }
            }
        }

    @Test
    fun `a purchase seen by the purchase update and the sync is acknowledged once`() =
        runTest {
            Given("an acknowledgement that has not answered yet") {
                val purchase = purchase("token")
                val billing = FakeBilling(purchases = listOf(purchase))
                billing.pendingAcknowledgement = CompletableDeferred()
                val controller = makeController(billing)

                When("Play reports the purchase and the sync sees it too") {
                    billing.purchaseResults.value = InternalPurchaseResult.Purchased(purchase)
                    controller.restorePurchases()

                    Then("only one acknowledgement is in flight") {
                        assertEquals(0, billing.acknowledgeCalls)
                        assertEquals(1, billing.acknowledgingCount(controller))
                    }
                }
                billing.pendingAcknowledgement!!.complete(Result.success(Unit))
            }
        }

    @Test
    fun `a purchase update from outside our flow re-syncs the subscription status`() =
        runTest {
            Given("a running controller") {
                val billing = FakeBilling()
                makeController(billing)
                advanceUntilIdle()
                val queriesBefore = billing.purchaseQueries

                When("Play reports a purchase we didn't launch (e.g. a pending purchase settling)") {
                    billing.purchases = listOf(purchase("settled"))
                    billing.purchaseResults.value = InternalPurchaseResult.Purchased(billing.purchases.first())
                    advanceUntilIdle()

                    Then("purchases are queried again and the purchase acknowledged") {
                        assertTrue(billing.purchaseQueries > queriesBefore)
                        assertTrue("settled" in billing.acknowledged)
                    }
                }
            }
        }

    private fun FakeBilling.acknowledgingCount(controller: AutomaticPurchaseController): Int {
        val field = AutomaticPurchaseController::class.java.getDeclaredField("acknowledgingTokens")
        field.isAccessible = true
        return (field.get(controller) as Set<*>).size
    }

}
