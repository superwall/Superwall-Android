@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.IOScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleBillingWrapperProductCacheTest {
    private val liveId = "product1:basePlan1:sw-auto"
    private val missingId = "product2:basePlan1:sw-auto"
    private val lifecycleObserver = AppLifecycleObserver()
    private var now = 1_000_000L

    private val okResult: BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .setDebugMessage("")
            .build()

    /** A connected client that only knows about `product1`. */
    private class FakeClient(
        okResult: BillingResult,
    ) {
        var queries = 0
        private val details =
            mockk<ProductDetails>(relaxed = true) {
                every { productId } returns "product1"
            }
        val client: BillingClient =
            mockk(relaxed = true) {
                every { isReady } returns true
                every { connectionState } returns BillingClient.ConnectionState.CONNECTED
                every { queryProductDetailsAsync(any(), any()) } answers {
                    queries++
                    val result =
                        mockk<QueryProductDetailsResult>(relaxed = true) {
                            every { productDetailsList } returns listOf(details)
                        }
                    secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(okResult, result)
                }
            }
    }

    private fun TestScope.makeWrapper(fake: FakeClient): GoogleBillingWrapper {
        val factory =
            mockk<GoogleBillingWrapper.Factory> {
                every { makeHasExternalPurchaseController() } returns false
                every { makeHasInternalPurchaseController() } returns false
                every { makeSuperwallOptions() } returns SuperwallOptions()
            }
        return GoogleBillingWrapper(
            context = mockk(relaxed = true),
            ioScope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            appLifecycleObserver = lifecycleObserver,
            factory = factory,
            createBillingClient = { fake.client },
            currentTimeMillis = { now },
        ).also {
            runCurrent()
            it.onBillingSetupFinished(okResult)
            runCurrent()
        }
    }

    @Test
    fun `a missing product never fails later loads of the other products`() =
        runTest {
            Given("a store that knows product1 but not product2") {
                val fake = FakeClient(okResult)
                val wrapper = makeWrapper(fake)

                When("both are requested twice") {
                    val first = wrapper.awaitGetProducts(setOf(liveId, missingId))
                    val second = wrapper.awaitGetProducts(setOf(liveId, missingId))

                    Then("both calls return the product that exists") {
                        assertEquals(setOf(liveId), first.map { it.fullIdentifier }.toSet())
                        assertEquals(setOf(liveId), second.map { it.fullIdentifier }.toSet())
                    }
                    And("the second call is served from cache without asking Play again") {
                        assertEquals(2, fake.queries) // SUBS + INAPP for the first call only
                    }
                }
            }
        }

    @Test
    fun `a missing product is asked for again once its ttl has passed`() =
        runTest {
            Given("a product that Play didn't return") {
                val fake = FakeClient(okResult)
                val wrapper = makeWrapper(fake)
                wrapper.awaitGetProducts(setOf(missingId))
                assertEquals(2, fake.queries)

                When("it is requested again within the ttl") {
                    now += MISSING_PRODUCT_TTL_MS - 1
                    val result = wrapper.awaitGetProducts(setOf(missingId))

                    Then("Play isn't queried and the result is empty, not an error") {
                        assertEquals(emptySet<String>(), result.map { it.fullIdentifier }.toSet())
                        assertEquals(2, fake.queries)
                    }
                }

                When("it is requested after the ttl") {
                    now += 2
                    wrapper.awaitGetProducts(setOf(missingId))

                    Then("Play is queried again") {
                        assertEquals(4, fake.queries)
                    }
                }
            }
        }

    @Test
    fun `clearing the cache makes the next load query Play again`() =
        runTest {
            Given("a wrapper with a cached product and a remembered miss") {
                val fake = FakeClient(okResult)
                val wrapper = makeWrapper(fake)
                wrapper.awaitGetProducts(setOf(liveId, missingId))

                When("the cache is cleared and the products requested again") {
                    wrapper.clearProductsCache()
                    wrapper.awaitGetProducts(setOf(liveId, missingId))

                    Then("Play is queried again") {
                        assertEquals(4, fake.queries)
                    }
                }
            }
        }

    @Test
    fun `the product cache belongs to the wrapper instance`() =
        runTest {
            Given("one wrapper that has cached a product") {
                val fake = FakeClient(okResult)
                makeWrapper(fake).awaitGetProducts(setOf(liveId))

                When("a new wrapper (e.g. after reconfigure) requests the same product") {
                    makeWrapper(fake).awaitGetProducts(setOf(liveId))

                    Then("it queries Play rather than reusing the old wrapper's cache") {
                        assertEquals(4, fake.queries)
                    }
                }
            }
        }
}
