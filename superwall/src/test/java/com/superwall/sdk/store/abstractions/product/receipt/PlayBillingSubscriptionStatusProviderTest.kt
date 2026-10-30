package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayBillingSubscriptionStatusProviderTest {
    private val provider = PlayBillingSubscriptionStatusProvider(billing = mockk(relaxed = true))
    private val dayMs = 24L * 60 * 60 * 1000

    private fun purchase(
        state: Int,
        purchaseTime: Long = System.currentTimeMillis(),
        autoRenewing: Boolean = true,
    ): Purchase =
        mockk {
            every { purchaseState } returns state
            every { this@mockk.purchaseTime } returns purchaseTime
            every { isAutoRenewing } returns autoRenewing
            every { products } returns listOf("monthly")
        }

    @Test
    fun `a renewing subscriber months after the first purchase is SUBSCRIBED`() =
        runTest {
            Given("a monthly subscription whose token was first purchased 3 months ago") {
                val renewed = purchase(PurchaseState.PURCHASED, purchaseTime = System.currentTimeMillis() - 90 * dayMs)

                When("the state is computed") {
                    val state = provider.getSubscriptionState(renewed, product = null)

                    Then("it is SUBSCRIBED, not EXPIRED") {
                        assertEquals(LatestSubscriptionState.SUBSCRIBED, state)
                    }
                }
            }
        }

    @Test
    fun `a cancelled subscription still returned by Play is SUBSCRIBED`() =
        runTest {
            Given("a purchased subscription that won't renew") {
                val cancelled = purchase(PurchaseState.PURCHASED, autoRenewing = false)

                Then("it is entitled until Play stops returning it") {
                    assertEquals(LatestSubscriptionState.SUBSCRIBED, provider.getSubscriptionState(cancelled, null))
                }
            }
        }

    @Test
    fun `a pending purchase is not reported as a grace period`() =
        runTest {
            Given("a pending, auto-renewing purchase") {
                val pending = purchase(PurchaseState.PENDING, autoRenewing = true)

                Then("the state is UNKNOWN") {
                    assertEquals(LatestSubscriptionState.UNKNOWN, provider.getSubscriptionState(pending, null))
                }
            }
        }

    @Test
    fun `an unspecified purchase is REVOKED`() =
        runTest {
            Given("a purchase in an unspecified state") {
                val unspecified = purchase(PurchaseState.UNSPECIFIED_STATE)

                Then("the state is REVOKED") {
                    assertEquals(LatestSubscriptionState.REVOKED, provider.getSubscriptionState(unspecified, null))
                }
            }
        }
}
