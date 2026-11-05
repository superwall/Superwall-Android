package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.billing.BillingError
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.storage.LatestDeviceCustomerInfo
import com.superwall.sdk.storage.PlayEntitlementHoldDates
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredTransactionHistory
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.product.SubscriptionPeriod
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ReceiptManagerSubscriptionStateTest {
    private class MapStorage : Storage {
        val values = mutableMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> read(storable: Storable<T>): T? = values[storable.key] as T?

        override fun <T : Any> write(
            storable: Storable<T>,
            data: T,
        ) {
            values[storable.key] = data
        }

        override fun <T : Any> delete(storable: Storable<T>) {
            values.remove(storable.key)
        }

        override fun clean() = values.clear()
    }

    private val billing = mockk<Billing>(relaxed = true)
    private val storage = MapStorage()
    private val now = 1_800_000_000_000L
    private val month = SubscriptionPeriod(1, SubscriptionPeriod.Unit.month)
    private val fullId = "monthly:base:sw-auto"
    private val entitlements = mapOf(fullId to setOf(Entitlement("pro")))

    private fun manager() =
        ReceiptManager(
            delegate = null,
            billing = billing,
            // Never advanced, so the init-time refresh doesn't race the test.
            ioScope = IOScope(StandardTestDispatcher()),
            storage = storage,
            customerInfoManager = { mockk(relaxed = true) },
            currentTimeMillis = { now },
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

    private fun purchasedSubscription(purchaseTime: Long = System.currentTimeMillis()): Purchase =
        mockk(relaxed = true) {
            every { purchaseState } returns PurchaseState.PURCHASED
            every { this@mockk.purchaseTime } returns purchaseTime
            every { isAutoRenewing } returns true
            every { products } returns listOf("monthly")
            every { orderId } returns "GPA.1"
            every { purchaseToken } returns "token"
        }

    private fun monthlyProduct(): StoreProduct =
        mockk(relaxed = true) {
            every { fullIdentifier } returns fullId
            every { productIdentifier } returns "monthly"
            every { subscriptionPeriod } returns month
            every { rawStoreProduct } returns
                mockk(relaxed = true) {
                    every { underlyingProductDetails.productType } returns ProductType.SUBS
                }
        }

    @Test
    fun `no current purchase but a subscription in history is EXPIRED`() =
        runTest {
            Given("Play answers with no purchases and history has an old subscription") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(emptyList())
                storage.write(StoredTransactionHistory, UserTransactionHistory(mapOf("GPA.1" to storedSubscription())))

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
            Given("Play answers with no purchases and there is no history") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(emptyList())

                Then("the state is UNKNOWN") {
                    assertEquals(LatestSubscriptionState.UNKNOWN, manager().lastSubscriptionProperties())
                }
            }
        }

    @Test
    fun `a failed read is not reported as EXPIRED`() =
        runTest {
            Given("Play can't be asked (e.g. the client is still connecting) and history has a subscription") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.failure(BillingError.UnknownError)
                storage.write(StoredTransactionHistory, UserTransactionHistory(mapOf("GPA.1" to storedSubscription())))

                Then("the state stays as it was") {
                    assertEquals(LatestSubscriptionState.UNKNOWN, manager().lastSubscriptionProperties())
                }
            }
        }

    @Test
    fun `cancellation during the product lookup is not swallowed`() =
        runTest {
            Given("Play returns a purchase and the product lookup is cancelled") {
                val purchase = purchasedSubscription(purchaseTime = System.currentTimeMillis())
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(listOf(purchase))
                coEvery { billing.awaitGetProducts(any()) } throws kotlinx.coroutines.CancellationException("cancelled")

                Then("the cancellation propagates instead of a computed state") {
                    org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                        kotlinx.coroutines.runBlocking { manager().lastSubscriptionProperties() }
                    }
                }
            }
        }

    @Test
    fun `a renewed purchase is SUBSCRIBED even when its product can't be loaded`() =
        runTest {
            Given("Play returns a subscription first bought 3 months ago, and product lookup fails") {
                val purchase = purchasedSubscription(purchaseTime = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(listOf(purchase))
                coEvery { billing.awaitGetProducts(any()) } throws BillingError.BillingNotAvailable("down")

                Then("the state is SUBSCRIBED") {
                    assertEquals(LatestSubscriptionState.SUBSCRIBED, manager().lastSubscriptionProperties())
                }
            }
        }

    @Test
    fun `loading an active subscription stores a hold date of one period from now`() =
        runTest {
            Given("Play returns an active monthly subscription that unlocks pro") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(listOf(purchasedSubscription()))
                coEvery { billing.awaitGetProducts(any()) } returns setOf(monthlyProduct())

                When("purchases are loaded") {
                    manager().loadPurchases(entitlements)

                    Then("pro can be held active for one period through a failed read") {
                        assertEquals(mapOf("pro" to now + month.toMillis()), storage.read(PlayEntitlementHoldDates))
                    }
                    And("the expiry shown to users stays unknown") {
                        val pro = storage.read(LatestDeviceCustomerInfo)!!.entitlements.single { it.id == "pro" }
                        assertTrue(pro.isActive)
                        assertNull(pro.expiresAt)
                    }
                }
            }
        }

    @Test
    fun `a failed read keeps the stored receipts instead of overwriting them`() =
        runTest {
            Given("receipts stored from an earlier successful read") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(listOf(purchasedSubscription()))
                coEvery { billing.awaitGetProducts(any()) } returns setOf(monthlyProduct())
                val manager = manager()
                manager.loadPurchases(entitlements)
                val history = storage.read(StoredTransactionHistory)
                val holdDates = storage.read(PlayEntitlementHoldDates)
                val purchases = manager.purchases
                assertTrue(purchases.isNotEmpty())

                When("the next read fails") {
                    coEvery { billing.queryAllPurchasesResult() } returns Result.failure(BillingError.UnknownError)
                    manager.loadPurchases(entitlements)

                    Then("history, hold dates and purchases are unchanged") {
                        assertEquals(history, storage.read(StoredTransactionHistory))
                        assertEquals(holdDates, storage.read(PlayEntitlementHoldDates))
                        assertEquals(purchases, manager.purchases)
                    }
                }
            }
        }

    @Test
    fun `after a reset the new user's receipts are rebuilt from Play`() =
        runTest {
            Given("a manager that loaded receipts, then the user scope was wiped") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(listOf(purchasedSubscription()))
                coEvery { billing.awaitGetProducts(any()) } returns setOf(monthlyProduct())
                val manager = manager()
                manager.loadPurchases(entitlements)
                storage.clean()

                When("it reloads for the new user") {
                    manager.reloadForNewUser(entitlements)

                    Then("the new user's history and device customer info come from Play again") {
                        assertTrue(storage.read(StoredTransactionHistory)!!.transactions.isNotEmpty())
                        assertTrue(storage.read(LatestDeviceCustomerInfo)!!.entitlements.any { it.id == "pro" && it.isActive })
                        assertEquals(LatestSubscriptionState.SUBSCRIBED, manager.experimentalProperties()["latestSubscriptionState"]?.let {
                            LatestSubscriptionState.valueOf(it as String)
                        })
                    }
                }
            }
        }

    @Test
    fun `after a reset with a failed read the old user's purchases are dropped`() =
        runTest {
            Given("a manager that loaded receipts for the old user") {
                coEvery { billing.queryAllPurchasesResult() } returns Result.success(listOf(purchasedSubscription()))
                coEvery { billing.awaitGetProducts(any()) } returns setOf(monthlyProduct())
                val manager = manager()
                manager.loadPurchases(entitlements)
                assertTrue(manager.purchases.isNotEmpty())

                When("it reloads for a new user but Play can't be asked") {
                    coEvery { billing.queryAllPurchasesResult() } returns Result.failure(BillingError.UnknownError)
                    manager.reloadForNewUser(entitlements)

                    Then("nothing from the old user's read is kept in memory") {
                        assertTrue(manager.purchases.isEmpty())
                        assertEquals("UNKNOWN", manager.experimentalProperties()["latestSubscriptionState"])
                    }
                }
            }
        }
}
