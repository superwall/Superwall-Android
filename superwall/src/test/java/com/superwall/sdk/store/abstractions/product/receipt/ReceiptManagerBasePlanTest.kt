package com.superwall.sdk.store.abstractions.product.receipt

import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.Billing
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.storage.LatestDeviceCustomerInfo
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredTransactionHistory
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.product.SubscriptionPeriod
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Play's `Purchase` only names the subscription, not the base plan, so the first base plan in the
 * config used to be reported whichever one was bought.
 */
class ReceiptManagerBasePlanTest {
    private val annual = "premium_sub:annual:sw-auto"
    private val monthly = "premium_sub:monthly:sw-auto"
    private val rawId = "premium_sub"
    private val purchaseTime = 1_762_000_000_000L
    private val premium = Entitlement("premium")

    private val monthlyMillis = SubscriptionPeriod(1, SubscriptionPeriod.Unit.month).toMillis
    private val annualMillis = SubscriptionPeriod(1, SubscriptionPeriod.Unit.year).toMillis

    private class FakeStorage : Storage {
        val values = mutableMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> read(storable: Storable<T>): T? = values[storable.key] as T?

        override fun <T : Any> write(
            storable: Storable<T>,
            data: T,
        ) {
            values[storable.key] = data
        }

        override fun clean() {
            values.clear()
        }
    }

    private fun purchase(
        token: String = "token",
        orderId: String = "GPA.1",
        time: Long = purchaseTime,
    ): Purchase =
        mockk {
            every { products } returns listOf(rawId)
            every { this@mockk.orderId } returns orderId
            every { purchaseToken } returns token
            every { purchaseTime } returns time
            every { purchaseState } returns PurchaseState.PURCHASED
            every { isAutoRenewing } returns true
            every { isAcknowledged } returns true
        }

    private fun storeProduct(fullId: String): StoreProduct {
        val details = mockk<ProductDetails> { every { productType } returns ProductType.SUBS }
        // A raw ID makes Play auto-select a base plan, here the annual one
        val period =
            if (fullId == monthly) {
                SubscriptionPeriod(1, SubscriptionPeriod.Unit.month)
            } else {
                SubscriptionPeriod(1, SubscriptionPeriod.Unit.year)
            }
        val raw =
            mockk<RawStoreProduct> {
                every { fullIdentifier } returns fullId
                every { productIdentifier } returns rawId
                every { underlyingProductDetails } returns details
                every { subscriptionPeriod } returns period
            }
        return mockk {
            every { fullIdentifier } returns fullId
            every { productIdentifier } returns rawId
            every { rawStoreProduct } returns raw
        }
    }

    private fun billing(purchases: () -> List<Purchase>): Billing =
        mockk(relaxed = true) {
            coEvery { queryAllPurchases() } answers { purchases() }
            coEvery { awaitGetProducts(any()) } answers {
                firstArg<Set<String>>().map { storeProduct(it) }.toSet()
            }
        }

    private fun receiptManager(
        storage: Storage,
        billing: Billing,
    ) = ReceiptManager(
        delegate = null,
        billing = billing,
        ioScope = IOScope(Dispatchers.Unconfined),
        storage = storage,
        customerInfoManager = { mockk(relaxed = true) },
        subscriptionStatusProvider = mockk(relaxed = true),
    )

    private val twoBasePlans get() = mapOf(annual to setOf(premium), monthly to setOf(premium))

    @Test
    fun `reports the base plan that was bought, not the first in the config`() =
        runTest {
            Given("a subscription with annual first in the config, and monthly bought") {
                val storage = FakeStorage()
                val manager = receiptManager(storage, billing { listOf(purchase()) })
                manager.recordPurchasedProduct(monthly, emptySet())

                When("purchases are loaded") {
                    manager.loadPurchases(twoBasePlans)
                    val info = storage.read(LatestDeviceCustomerInfo)!!
                    val entitlement = info.entitlements.single()

                    Then("the subscription names the monthly plan and its expiry") {
                        val subscription = info.subscriptions.single()
                        assertEquals(monthly, subscription.productId)
                        assertEquals(Date(purchaseTime + monthlyMillis), subscription.expirationDate)
                    }

                    And("so does the entitlement") {
                        assertEquals(monthly, entitlement.latestProductId)
                        assertEquals(Date(purchaseTime + monthlyMillis), entitlement.expiresAt)
                        assertTrue(entitlement.isActive)
                    }
                }
            }
        }

    @Test
    fun `the plan bought survives an app restart`() =
        runTest {
            Given("monthly was bought in a previous session") {
                val storage = FakeStorage()
                val play = billing { listOf(purchase()) }
                receiptManager(storage, play).recordPurchasedProduct(monthly, emptySet())

                When("a new session loads purchases") {
                    receiptManager(storage, play).loadPurchases(twoBasePlans)

                    Then("the subscription still names the monthly plan") {
                        assertEquals(
                            monthly,
                            storage
                                .read(LatestDeviceCustomerInfo)!!
                                .subscriptions
                                .single()
                                .productId,
                        )
                    }
                }
            }
        }

    @Test
    fun `claims no plan or expiry when the base plan bought is unknown`() =
        runTest {
            Given("a purchase with several possible base plans and nothing recorded for it") {
                val storage = FakeStorage()
                val manager = receiptManager(storage, billing { listOf(purchase()) })

                When("purchases are loaded") {
                    manager.loadPurchases(twoBasePlans)
                    val info = storage.read(LatestDeviceCustomerInfo)!!
                    val entitlement = info.entitlements.single()

                    Then("the subscription has the raw ID and no expiry") {
                        val subscription = info.subscriptions.single()
                        assertEquals(rawId, subscription.productId)
                        assertNull(subscription.expirationDate)
                    }

                    And("the entitlement is still active, without a plan or expiry") {
                        assertEquals("premium", entitlement.id)
                        assertTrue(entitlement.isActive)
                        assertEquals(rawId, entitlement.latestProductId)
                        assertNull(entitlement.expiresAt)
                    }
                }
            }
        }

    @Test
    fun `a single base plan is reported without being recorded`() =
        runTest {
            Given("a subscription with one base plan in the config") {
                val storage = FakeStorage()
                val manager = receiptManager(storage, billing { listOf(purchase()) })

                When("purchases are loaded") {
                    manager.loadPurchases(mapOf(annual to setOf(premium)))

                    Then("the subscription names that plan and its expiry") {
                        val subscription = storage.read(LatestDeviceCustomerInfo)!!.subscriptions.single()
                        assertEquals(annual, subscription.productId)
                        assertEquals(Date(purchaseTime + annualMillis), subscription.expirationDate)
                    }
                }
            }
        }

    @Test
    fun `grants only the entitlements of the plan bought`() =
        runTest {
            Given("base plans that unlock different entitlements, and monthly bought") {
                val storage = FakeStorage()
                val manager = receiptManager(storage, billing { listOf(purchase()) })
                manager.recordPurchasedProduct(monthly, emptySet())

                When("purchases are loaded") {
                    manager.loadPurchases(
                        mapOf(
                            annual to setOf(Entitlement("pro")),
                            monthly to setOf(Entitlement("basic")),
                        ),
                    )

                    Then("only the monthly plan's entitlement is active") {
                        val active =
                            storage
                                .read(LatestDeviceCustomerInfo)!!
                                .entitlements
                                .filter { it.isActive }
                        assertEquals(listOf("basic"), active.map { it.id })
                    }
                }
            }
        }

    @Test
    fun `corrects a wrong plan stored by an earlier SDK version`() =
        runTest {
            Given("an expired transaction stored with the annual plan and its inflated expiry") {
                val storage = FakeStorage()
                storage.write(
                    StoredTransactionHistory,
                    UserTransactionHistory(
                        mapOf(
                            "GPA.old" to
                                StoredEntitlementTransaction(
                                    productId = annual,
                                    transactionId = "GPA.old",
                                    purchaseDate = Date(purchaseTime),
                                    originalPurchaseDate = Date(purchaseTime),
                                    expirationDate = Date(purchaseTime + annualMillis),
                                    isRevoked = false,
                                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                                    willRenew = false,
                                    renewedAt = null,
                                    isInGracePeriod = false,
                                    isInBillingRetryPeriod = false,
                                    isActive = false,
                                ),
                        ),
                    ),
                )
                val newTime = purchaseTime + 1_000_000
                val manager =
                    receiptManager(
                        storage,
                        billing { listOf(purchase(token = "new", orderId = "GPA.new", time = newTime)) },
                    )

                When("monthly is bought afterwards") {
                    manager.recordPurchasedProduct(monthly, emptySet())
                    manager.loadPurchases(twoBasePlans)
                    val info = storage.read(LatestDeviceCustomerInfo)!!

                    Then("the old transaction no longer claims a plan or expiry") {
                        val old = info.subscriptions.single { it.transactionId == "GPA.old" }
                        assertEquals(rawId, old.productId)
                        assertNull(old.expirationDate)
                    }

                    And("the entitlement expires with the monthly plan") {
                        val entitlement = info.entitlements.single()
                        assertEquals(monthly, entitlement.latestProductId)
                        assertEquals(Date(newTime + monthlyMillis), entitlement.expiresAt)
                    }
                }
            }
        }

    @Test
    fun `a plan switch applies to the new purchase, not the one it replaces`() =
        runTest {
            Given("annual was bought, then switched to monthly before Play returns the new token") {
                val storage = FakeStorage()
                var purchases = listOf(purchase(token = "old", orderId = "GPA.old"))
                val manager = receiptManager(storage, billing { purchases })
                manager.recordPurchasedProduct(annual, emptySet())
                manager.recordPurchasedProduct(monthly, emptySet())

                When("Play still returns the old purchase") {
                    manager.loadPurchases(twoBasePlans)

                    Then("it keeps its annual plan") {
                        assertEquals(
                            annual,
                            storage
                                .read(LatestDeviceCustomerInfo)!!
                                .subscriptions
                                .single()
                                .productId,
                        )
                    }
                }

                When("Play returns the new purchase") {
                    purchases =
                        listOf(purchase(token = "new", orderId = "GPA.new", time = purchaseTime + 1_000_000))
                    manager.loadPurchases(twoBasePlans)

                    Then("it has the monthly plan") {
                        val subscription =
                            storage
                                .read(LatestDeviceCustomerInfo)!!
                                .subscriptions
                                .single { it.transactionId == "GPA.new" }
                        assertEquals(monthly, subscription.productId)
                    }
                }
            }
        }

    @Test
    fun `a deferred plan change doesn't relabel the purchase it replaces`() =
        runTest {
            Given("a purchase with no recorded plan, and a change to monthly that Play defers") {
                val storage = FakeStorage()
                var purchases = listOf(purchase(token = "old", orderId = "GPA.old"))
                val manager = receiptManager(storage, billing { purchases })
                manager.loadPurchases(twoBasePlans)
                manager.recordPurchasedProduct(monthly, manager.knownPurchaseTokens)

                When("Play still returns the existing purchase") {
                    manager.loadPurchases(twoBasePlans)

                    Then("it doesn't claim the plan that hasn't started") {
                        val subscription = storage.read(LatestDeviceCustomerInfo)!!.subscriptions.single()
                        assertEquals(rawId, subscription.productId)
                        assertNull(subscription.expirationDate)
                    }
                }

                When("the change takes effect with a new purchase") {
                    purchases =
                        listOf(purchase(token = "new", orderId = "GPA.new", time = purchaseTime + 1_000_000))
                    manager.loadPurchases(twoBasePlans)

                    Then("the new purchase has the monthly plan") {
                        val subscription =
                            storage
                                .read(LatestDeviceCustomerInfo)!!
                                .subscriptions
                                .single { it.transactionId == "GPA.new" }
                        assertEquals(monthly, subscription.productId)
                    }
                }
            }
        }

    @Test
    fun `a product with no specific base plan isn't recorded`() =
        runTest {
            Given("a purchase of a product that auto-selects its base plan") {
                val storage = FakeStorage()
                val manager = receiptManager(storage, billing { listOf(purchase()) })
                manager.recordPurchasedProduct("premium_sub:sw-auto:sw-auto", emptySet())

                When("purchases are loaded") {
                    manager.loadPurchases(twoBasePlans)

                    Then("the plan is still treated as unknown") {
                        val subscription = storage.read(LatestDeviceCustomerInfo)!!.subscriptions.single()
                        assertEquals(rawId, subscription.productId)
                        assertNull(subscription.expirationDate)
                    }
                }
            }
        }
}
