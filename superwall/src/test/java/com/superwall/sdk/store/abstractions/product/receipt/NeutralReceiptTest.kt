@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.BillingBackedStore
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralReceiptTest {
    private fun storePurchase(
        state: StorePurchaseState = StorePurchaseState.Purchased,
        autoRenewing: Boolean = true,
        purchaseTime: Long = System.currentTimeMillis(),
        token: String = "token-1",
    ) = StorePurchase(
        token = token,
        orderId = null,
        productIds = listOf("monthly"),
        purchaseTime = purchaseTime,
        state = state,
        autoRenewing = autoRenewing,
        acknowledged = true,
    )

    private fun product(type: String = ProductType.SUBS): StoreProduct {
        val details = mockk<ProductDetails> { every { productType } returns type }
        val raw =
            mockk<RawStoreProduct> {
                every { underlyingProductDetails } returns details
                every { subscriptionPeriod } returns null
                every { selectedOffer } returns null
            }
        return mockk {
            every { rawStoreProduct } returns raw
            every { fullIdentifier } returns "monthly:base"
            every { productIdentifier } returns "monthly"
            every { trialPeriodEndDate } returns null
        }
    }

    @Test
    fun `a neutral transaction reads the purchase like the Play adapter did`() {
        Given("a pending, renewing subscription purchase without an order id") {
            val purchase = storePurchase(state = StorePurchaseState.Pending)
            val transaction =
                When("it becomes a transaction") {
                    StorePurchaseTransaction.fromPurchase(purchase, mapOf("monthly" to product())).single()
                }
            Then("it uses the full id, the token and the pending state") {
                assertEquals("monthly:base", transaction.productId)
                assertEquals("token-1", transaction.transactionId)
                assertTrue(transaction.isRevoked)
                assertTrue(transaction.isInGracePeriod)
                assertFalse(transaction.isActive)
                assertEquals(EntitlementTransactionType.AUTO_RENEWABLE, transaction.productType)
            }
        }
    }

    @Test
    fun `an unknown product keeps its raw id and counts as consumable`() {
        val transaction = StorePurchaseTransaction.fromPurchase(storePurchase(), emptyMap()).single()
        assertEquals("monthly", transaction.productId)
        assertEquals(EntitlementTransactionType.CONSUMABLE, transaction.productType)
        assertNull(transaction.expirationDate)
    }

    @Test
    fun `default status reads the neutral purchase state`() =
        runTest {
            assertEquals(
                LatestSubscriptionState.REVOKED,
                DefaultSubscriptionStatus.subscriptionState(storePurchase(state = StorePurchaseState.Unknown), product()),
            )
            assertEquals(
                LatestSubscriptionState.GRACE_PERIOD,
                DefaultSubscriptionStatus.subscriptionState(storePurchase(state = StorePurchaseState.Pending), product()),
            )
            assertEquals(LatestSubscriptionState.SUBSCRIBED, DefaultSubscriptionStatus.subscriptionState(storePurchase(), product()))
            assertEquals(
                LatestSubscriptionState.EXPIRED,
                DefaultSubscriptionStatus.subscriptionState(storePurchase(autoRenewing = false), product()),
            )
            assertTrue(DefaultSubscriptionStatus.isActive(storePurchase(), product(ProductType.INAPP)))
            assertFalse(DefaultSubscriptionStatus.isActive(storePurchase(state = StorePurchaseState.Pending), product()))
            assertEquals(LatestPeriodType.SUBSCRIPTION, DefaultSubscriptionStatus.offerType(storePurchase(), product()))
            assertEquals(
                LatestPeriodType.REVOKED,
                DefaultSubscriptionStatus.offerType(storePurchase(state = StorePurchaseState.Unknown), product()),
            )
        }

    @Test
    fun `the default public provider runs the SDK's own logic`() {
        val provider = ProviderSubscriptionStatus.of(PlayBillingSubscriptionStatusProvider(mockk())) { null }
        assertSame(DefaultSubscriptionStatus, provider)
    }

    @Test
    fun `a custom provider is handed the original Play purchase`() =
        runTest {
            Given("a custom provider and the Play purchase behind a neutral one") {
                val play = mockk<Purchase>()
                val custom =
                    mockk<SubscriptionStatusProvider> {
                        coEvery { getSubscriptionState(play, any()) } returns LatestSubscriptionState.BILLING_RETRY
                        every { getWillAutoRenew(play) } returns false
                    }
                val status = ProviderSubscriptionStatus.of(custom) { if (it.token == "token-1") play else null }

                val state = When("the state of that purchase is read") { status.subscriptionState(storePurchase(), product()) }
                Then("the provider answers for that purchase") {
                    assertEquals(LatestSubscriptionState.BILLING_RETRY, state)
                    assertFalse(status.willAutoRenew(storePurchase()))
                    coVerify { custom.getSubscriptionState(play, any()) }
                }
                Then("a purchase never seen in Play form falls back to the SDK's logic") {
                    assertTrue(status.willAutoRenew(storePurchase(token = "other")))
                }
            }
        }

    @Test
    fun `a public Billing is read as a store that remembers the Play purchases`() =
        runTest {
            val play =
                mockk<Purchase> {
                    every { purchaseToken } returns "token-1"
                    every { orderId } returns "GPA.1"
                    every { products } returns listOf("monthly")
                    every { purchaseTime } returns 1L
                    every { purchaseState } returns Purchase.PurchaseState.PURCHASED
                    every { isAutoRenewing } returns true
                    every { isAcknowledged } returns true
                }
            val billing =
                mockk<Billing>(relaxed = true) {
                    coEvery { queryAllPurchases() } returns listOf(play)
                    coEvery { awaitGetProducts(setOf("monthly:base", "gone")) } returns setOf(product())
                }
            val (store, purchases) = BillingBackedStore.storeOf(billing)

            val owned = store.ownedPurchases()
            assertEquals(listOf("monthly"), owned.single().productIds)
            assertSame(play, purchases.native("token-1"))
            assertEquals(setOf("gone"), store.products(setOf("monthly:base", "gone")).missingIds)
        }
}
