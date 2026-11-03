@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing.google

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.transactions.ObservedReport
import com.android.billingclient.api.ProductDetails
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseConversionsTest {
    private fun purchase(
        token: String = "token-1",
        state: Int = Purchase.PurchaseState.PURCHASED,
    ): Purchase =
        mockk {
            every { purchaseToken } returns token
            every { orderId } returns "GPA.1"
            every { products } returns listOf("monthly", "addon")
            every { purchaseTime } returns 1_700L
            every { purchaseState } returns state
            every { isAutoRenewing } returns true
            every { isAcknowledged } returns false
        }

    private fun result(code: Int): BillingResult = BillingResult.newBuilder().setResponseCode(code).build()

    @Test
    fun `a Play purchase converts field by field`() {
        Given("a purchased Play purchase") {
            val play = purchase()

            When("it is converted") {
                val neutral = play.toStorePurchase()

                Then("every field is carried over") {
                    assertEquals(
                        StorePurchase(
                            token = "token-1",
                            orderId = "GPA.1",
                            productIds = listOf("monthly", "addon"),
                            purchaseTime = 1_700L,
                            state = StorePurchaseState.Purchased,
                            autoRenewing = true,
                            acknowledged = false,
                        ),
                        neutral,
                    )
                }
            }
        }
    }

    @Test
    fun `Play purchase states map to the neutral states`() {
        Given("a Play value") { Then("purchased, pending and anything else map to Purchased, Pending and Unknown") {
            assertSame(StorePurchaseState.Purchased, purchaseStateOf(Purchase.PurchaseState.PURCHASED))
            assertSame(StorePurchaseState.Pending, purchaseStateOf(Purchase.PurchaseState.PENDING))
            assertSame(StorePurchaseState.Unknown, purchaseStateOf(Purchase.PurchaseState.UNSPECIFIED_STATE))
        } }
    }

    @Test
    fun `a successful flow with purchases is Purchased`() {
        Given("a Play value") { Then("the purchases are converted") {
            val outcome = purchaseResultOf(result(BillingClient.BillingResponseCode.OK), listOf(purchase()))
            assertEquals(listOf("token-1"), (outcome as StorePurchaseResult.Purchased).purchases.map { it.token })
        } }
    }

    @Test
    fun `a cancelled flow is Cancelled`() {
        Given("a Play value") { Then("the outcome is Cancelled") {
            assertSame(StorePurchaseResult.Cancelled, purchaseResultOf(result(BillingClient.BillingResponseCode.USER_CANCELED), null))
        } }
    }

    @Test
    fun `any other response, or OK without purchases, fails with the response code`() {
        Given("a Play value") { Then("the failure carries the code as its message") {
            val error = purchaseResultOf(result(BillingClient.BillingResponseCode.ERROR), null)
            assertEquals("${BillingClient.BillingResponseCode.ERROR}", (error as StorePurchaseResult.Failed).error.message)
            assertTrue(purchaseResultOf(result(BillingClient.BillingResponseCode.OK), null) is StorePurchaseResult.Failed)
        } }
    }

    @Test
    fun `recorded purchases give back the original Play purchase by token`() {
        Given("purchases recorded") {
            val purchases = PlayPurchases()
            val first = purchase("a")
            val neutral = purchases.record(listOf(first, purchase("b")))

            Then("their neutral forms keep the order and the originals are found by token") {
                assertEquals(listOf("a", "b"), neutral.map { it.token })
                assertSame(first, purchases.native("a"))
                assertEquals(listOf(first), purchases.native(listOf(neutral.first())))
                assertEquals(null, purchases.native("unknown"))
            }
        }
    }

    @Test
    fun `listener results convert to neutral updates, recording purchases`() {
        Given("each kind of listener result") {
            val recorder = PlayPurchases()
            val error = IllegalStateException("boom")

            Then("each maps to its neutral update") {
                val purchased = InternalPurchaseResult.Purchased(purchase()).toStorePurchaseUpdate(recorder)
                assertEquals("token-1", (purchased as StorePurchaseUpdate.Purchased).purchase.token)
                assertEquals("token-1", recorder.native("token-1")?.purchaseToken)
                assertSame(StorePurchaseUpdate.Cancelled, InternalPurchaseResult.Cancelled.toStorePurchaseUpdate())
                assertSame(StorePurchaseUpdate.Pending, InternalPurchaseResult.Pending.toStorePurchaseUpdate())
                assertSame(StorePurchaseUpdate.Restored, InternalPurchaseResult.Restored.toStorePurchaseUpdate())
                assertSame(error, (InternalPurchaseResult.Failed(error).toStorePurchaseUpdate() as StorePurchaseUpdate.Failed).error)
            }
        }
    }

    @Test
    fun `an app's purchase result becomes one update per purchase and a report of them`() {
        Given("an OK result with two purchases") {
            val state =
                PurchasingObserverState.PurchaseResult(
                    result(BillingClient.BillingResponseCode.OK),
                    listOf(purchase("a"), purchase("b")),
                )

            Then("there is one purchased update each, and the report carries both") {
                assertEquals(listOf("a", "b"), state.toStorePurchaseUpdates().map { (it as StorePurchaseUpdate.Purchased).purchase.token })
                assertEquals(listOf("a", "b"), (state.toObservedReport() as ObservedReport.Result).purchases?.map { it.token })
            }
        }
        Given("a cancelled result and a failed one") {
            val cancelled = PurchasingObserverState.PurchaseResult(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
            val failed = PurchasingObserverState.PurchaseResult(result(BillingClient.BillingResponseCode.ERROR), null)

            Then("they become a single cancel and a single failure carrying the code") {
                assertEquals(listOf(StorePurchaseUpdate.Cancelled), cancelled.toStorePurchaseUpdates())
                val failure = failed.toStorePurchaseUpdates().single() as StorePurchaseUpdate.Failed
                assertEquals(BillingClient.BillingResponseCode.ERROR.toString(), failure.error.message)
            }
        }
    }

    @Test
    fun `an app's purchase error reports the product and error, and a start reports nothing`() {
        Given("Play product details for pro") {
            val details = mockk<ProductDetails>(relaxed = true) { every { productId } returns "pro" }
            val error = IllegalStateException("declined")

            Then("the error report names pro and keeps the error") {
                val report = PurchasingObserverState.PurchaseError(details, error).toObservedReport() as ObservedReport.Error
                assertSame(error, report.error)
                assertEquals("pro", report.product.fullIdentifier)
                assertEquals(null, PurchasingObserverState.PurchaseWillBegin(details).toObservedReport())
            }
        }
    }
}
