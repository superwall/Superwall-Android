@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.BillingError
import com.superwall.sdk.billing.GoogleBillingWrapper
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.CrossplatformProduct
import com.superwall.sdk.models.product.Offer
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeBehavior
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reproduces a device without the Play Store: the real [GoogleBillingWrapper]
 * is wired to a [BillingClient] whose setup finishes with BILLING_UNAVAILABLE,
 * and the full StoreManager + TestMode chain is exercised against it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestModeBillingUnavailableIntegrationTest {
    @Before
    fun setup() {
        GoogleBillingWrapper.clearProductsCache()
    }

    @After
    fun tearDown() {
        GoogleBillingWrapper.clearProductsCache()
    }

    private fun billingResult(
        code: Int,
        message: String = "",
    ): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage(message)
            .build()

    private fun kotlinx.coroutines.test.TestScope.makeWrapper(): GoogleBillingWrapper {
        val mockClient =
            mockk<BillingClient>(relaxed = true) {
                every { isReady } returns false
            }
        val factory =
            mockk<GoogleBillingWrapper.Factory> {
                every { makeHasExternalPurchaseController() } returns false
                every { makeHasInternalPurchaseController() } returns false
                every { makeSuperwallOptions() } returns SuperwallOptions()
            }
        return GoogleBillingWrapper(
            context = mockk(relaxed = true),
            ioScope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            appLifecycleObserver = AppLifecycleObserver(),
            factory = factory,
            createBillingClient = { mockClient },
        )
    }

    private fun makeActiveTestMode(): TestMode {
        val testMode = TestMode(mockk(relaxed = true), isTestEnvironment = false)
        testMode.evaluateTestMode(
            config = mockk(relaxed = true),
            bundleId = "com.app",
            appUserId = null,
            aliasId = null,
            testModeBehavior = TestModeBehavior.ALWAYS,
        )
        return testMode
    }

    private fun makePaywall(): Paywall =
        Paywall.stub().copy(
            productIds = listOf("product1:basePlan1:sw-auto", "product2:basePlan1:sw-auto"),
            _productItemsV3 =
                listOf(
                    CrossplatformProduct(
                        compositeId = "product1:basePlan1:sw-auto",
                        storeProduct =
                            CrossplatformProduct.StoreProduct.PlayStore(
                                productIdentifier = "product1",
                                basePlanIdentifier = "basePlan1",
                                offer = Offer.Automatic(),
                            ),
                        entitlements = listOf(Entitlement("entitlement1")),
                        name = "Item1",
                    ),
                    CrossplatformProduct(
                        compositeId = "product2:basePlan1:sw-auto",
                        storeProduct =
                            CrossplatformProduct.StoreProduct.PlayStore(
                                productIdentifier = "product2",
                                basePlanIdentifier = "basePlan1",
                                offer = Offer.Automatic(),
                            ),
                        entitlements = listOf(Entitlement("entitlement1")),
                        name = "Item2",
                    ),
                ),
        )

    @Test
    fun `test paywall loads from test catalog when billing setup reports BILLING_UNAVAILABLE`() =
        runTest {
            Given("test mode is active with a full catalog on a device without the Play Store") {
                val wrapper = makeWrapper()
                val testMode = makeActiveTestMode()
                testMode.setTestProducts(
                    mapOf(
                        "product1:basePlan1:sw-auto" to
                            mockk<StoreProduct> {
                                every { fullIdentifier } returns "product1:basePlan1:sw-auto"
                            },
                        "product2:basePlan1:sw-auto" to
                            mockk<StoreProduct> {
                                every { fullIdentifier } returns "product2:basePlan1:sw-auto"
                            },
                    ),
                )
                val storeManager =
                    StoreManager(
                        purchaseController = mockk(),
                        billing = wrapper,
                        receiptManagerFactory = { mockk(relaxed = true) },
                        track = {},
                        testMode = testMode,
                    )

                When("a paywall's products are requested") {
                    val result = storeManager.getProducts(null, makePaywall(), null)

                    Then("all products resolve from the test catalog") {
                        assertEquals(2, result.productsByFullId.size)
                    }
                }
            }
        }

    @Test
    fun `test partial catalog survives BILLING_UNAVAILABLE from the real billing wrapper`() =
        runTest {
            Given("test mode is active but the catalog only covers one of two products") {
                val wrapper = makeWrapper()
                val testMode = makeActiveTestMode()
                testMode.setTestProducts(
                    mapOf(
                        "product1:basePlan1:sw-auto" to
                            mockk<StoreProduct> {
                                every { fullIdentifier } returns "product1:basePlan1:sw-auto"
                            },
                    ),
                )
                val storeManager =
                    StoreManager(
                        purchaseController = mockk(),
                        billing = wrapper,
                        receiptManagerFactory = { mockk(relaxed = true) },
                        track = {},
                        testMode = testMode,
                    )

                When("a paywall load falls through to billing for the uncovered product") {
                    val job = async { storeManager.getProducts(null, makePaywall(), null) }
                    advanceUntilIdle()

                    And("billing setup finishes with BILLING_UNAVAILABLE") {
                        wrapper.onBillingSetupFinished(
                            billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
                        )
                        advanceUntilIdle()
                    }

                    Then("the paywall still gets the test product instead of failing") {
                        val result = job.await()
                        assertEquals(
                            setOf("product1:basePlan1:sw-auto"),
                            result.productsByFullId.keys,
                        )
                    }

                    And("a second load succeeds despite the permanently cached billing failure") {
                        val job2 = async { storeManager.getProducts(null, makePaywall(), null) }
                        advanceUntilIdle()
                        assertEquals(
                            setOf("product1:basePlan1:sw-auto"),
                            job2.await().productsByFullId.keys,
                        )
                    }
                }
            }
        }

    @Test
    fun `test without test mode BILLING_UNAVAILABLE still fails the paywall load`() =
        runTest {
            Given("no test mode on a device without the Play Store") {
                val wrapper = makeWrapper()
                val storeManager =
                    StoreManager(
                        purchaseController = mockk(),
                        billing = wrapper,
                        receiptManagerFactory = { mockk(relaxed = true) },
                        track = {},
                    )

                When("a paywall's products are requested and billing setup fails") {
                    val job = async { runCatching { storeManager.getProducts(null, makePaywall(), null) } }
                    advanceUntilIdle()
                    wrapper.onBillingSetupFinished(
                        billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
                    )
                    advanceUntilIdle()

                    Then("the load fails with BillingNotAvailable, as before") {
                        val outcome = job.await()
                        assertTrue(outcome.isFailure)
                        assertTrue(outcome.exceptionOrNull() is BillingError.BillingNotAvailable)
                    }
                }
            }
        }
}
