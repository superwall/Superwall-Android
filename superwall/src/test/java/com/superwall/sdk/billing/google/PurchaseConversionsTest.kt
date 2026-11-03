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
}
