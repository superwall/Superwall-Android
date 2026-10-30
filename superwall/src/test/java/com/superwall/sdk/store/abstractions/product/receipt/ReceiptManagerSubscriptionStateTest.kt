package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.BillingError
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredTransactionHistory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class ReceiptManagerSubscriptionStateTest {
    private val billing = mockk<Billing>(relaxed = true)
    private val storage = mockk<Storage>(relaxed = true)

    private fun manager() =
        ReceiptManager(
            delegate = null,
            billing = billing,
            // Never advanced, so the init-time refresh doesn't race the test.
            ioScope = IOScope(StandardTestDispatcher()),
            storage = storage,
            customerInfoManager = { mockk(relaxed = true) },
        )

    private fun storedSubscription() =
        StoredEntitlementTransaction(
            productId = "monthly",
            transactionId = "GPA.1",
            purchaseDate = Date(0),
            originalPurchaseDate = Date(0),
            expirationDate = null,
            isRevoked = false,
            productType = EntitlementTransactionType.AUTO_RENEWABLE,
            willRenew = false,
            renewedAt = null,
            isInGracePeriod = false,
            isInBillingRetryPeriod = false,
            isActive = false,
        )

    @Test
    fun `no current purchase but a subscription in history is EXPIRED`() =
        runTest {
            Given("Play returns nothing and history has an old subscription") {
                coEvery { billing.queryAllPurchases() } returns emptyList()
                every { storage.read(StoredTransactionHistory) } returns
                    UserTransactionHistory(mapOf("GPA.1" to storedSubscription()))

                When("the latest subscription state is computed") {
                    val state = manager().lastSubscriptionProperties()

                    Then("it is EXPIRED") {
                        assertEquals(LatestSubscriptionState.EXPIRED, state)
                    }
                }
            }
        }

    @Test
    fun `no current purchase and no history is UNKNOWN`() =
        runTest {
            Given("Play returns nothing and there is no history") {
                coEvery { billing.queryAllPurchases() } returns emptyList()
                every { storage.read(StoredTransactionHistory) } returns null

                Then("the state is UNKNOWN") {
                    assertEquals(LatestSubscriptionState.UNKNOWN, manager().lastSubscriptionProperties())
                }
            }
        }

    @Test
    fun `a renewed purchase is SUBSCRIBED even when its product can't be loaded`() =
        runTest {
            Given("Play returns a subscription first bought 3 months ago, and product lookup fails") {
                val purchase =
                    mockk<Purchase> {
                        every { purchaseState } returns PurchaseState.PURCHASED
                        every { purchaseTime } returns System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                        every { isAutoRenewing } returns true
                        every { products } returns listOf("monthly")
                    }
                coEvery { billing.queryAllPurchases() } returns listOf(purchase)
                coEvery { billing.awaitGetProducts(any()) } throws BillingError.BillingNotAvailable("down")

                Then("the state is SUBSCRIBED") {
                    assertEquals(LatestSubscriptionState.SUBSCRIBED, manager().lastSubscriptionProperties())
                }
            }
        }
}
