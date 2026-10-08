@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the connection edge cases that used to leave a product request without any
 * callback: a query Play never answers, a disconnect while requests are queued, and a
 * duplicate connection attempt reported as DEVELOPER_ERROR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoogleBillingWrapperConnectionTest {
    private val productId = "product1:basePlan1:sw-auto"
    private val lifecycleObserver = AppLifecycleObserver()

    @Before
    fun setup() {
        GoogleBillingWrapper.clearProductsCache()
    }

    @After
    fun tearDown() {
        GoogleBillingWrapper.clearProductsCache()
    }

    private fun billingResult(code: Int): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage("")
            .build()

    /** A billing client whose readiness and connection state the test controls. */
    private class FakeClient {
        var ready = false
        var connection = BillingClient.ConnectionState.DISCONNECTED
        private var startConnections = 0

        /** One entry per product type queried; the wrapper asks for SUBS and INAPP separately. */
        val productListeners = mutableListOf<ProductDetailsResponseListener>()
        val client: BillingClient =
            mockk(relaxed = true) {
                every { isReady } answers { this@FakeClient.ready }
                every { connectionState } answers { this@FakeClient.connection }
                every { startConnection(any()) } answers { this@FakeClient.startConnections++ }
                every { queryProductDetailsAsync(any(), any()) } answers {
                    this@FakeClient.productListeners += secondArg<ProductDetailsResponseListener>()
                }
            }

        fun connect() {
            ready = true
            connection = BillingClient.ConnectionState.CONNECTED
        }

        fun startConnectionCalls(): Int = startConnections
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
        )
    }

    @Test
    fun `a product query Play never answers times out instead of hanging`() =
        runTest {
            Given("a connected billing client that never answers product queries") {
                val fake = FakeClient().apply { connect() }
                val wrapper = makeWrapper(fake)
                runCurrent()

                When("products are requested") {
                    val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                    runCurrent()

                    Then("the request waits while the timeout hasn't passed") {
                        assertEquals(2, fake.productListeners.size)
                        assertFalse(job.isCompleted)
                    }

                    And("fails with a timeout once it has") {
                        advanceTimeBy(PRODUCTS_QUERY_TIMEOUT_MS + 1)
                        runCurrent()
                        assertTrue(job.isCompleted)
                        assertTrue(job.await().exceptionOrNull() is BillingError.Timeout)
                    }

                    And("the timeout isn't cached, so the next request queries Play again") {
                        val retry = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                        runCurrent()
                        assertEquals(4, fake.productListeners.size)

                        And("late answers, including to the timed-out request, are handled without crashing") {
                            fake.productListeners.forEach {
                                it.onProductDetailsResponse(
                                    billingResult(BillingClient.BillingResponseCode.OK),
                                    mockk<QueryProductDetailsResult>(relaxed = true),
                                )
                            }
                            runCurrent()
                            assertTrue(retry.isCompleted)
                            assertTrue(retry.await().isSuccess)
                        }
                    }
                }
            }
        }

    @Test
    fun `a disconnect with queued requests schedules a reconnect`() =
        runTest {
            Given("a disconnected billing client with a product request waiting on it") {
                val fake = FakeClient()
                val wrapper = makeWrapper(fake)
                runCurrent()
                val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                runCurrent()
                val connectionsBefore = fake.startConnectionCalls()

                When("the billing service disconnects") {
                    wrapper.onBillingServiceDisconnected()
                    advanceTimeBy(RECONNECT_TIMER_START_MILLISECONDS + 1)
                    runCurrent()

                    Then("a reconnect is attempted and the request keeps waiting for it") {
                        assertEquals(connectionsBefore + 1, fake.startConnectionCalls())
                        assertFalse(job.isCompleted)
                    }
                }

                When("the reconnect succeeds") {
                    fake.connect()
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
                    runCurrent()

                    Then("the queued request runs") {
                        assertEquals(2, fake.productListeners.size)
                    }
                }
            }
        }

    @Test
    fun `a disconnect with nothing queued does not reconnect`() =
        runTest {
            Given("a billing client with no requests waiting") {
                val fake = FakeClient().apply { connect() }
                val wrapper = makeWrapper(fake)
                runCurrent()
                val connectionsBefore = fake.startConnectionCalls()

                When("the billing service disconnects") {
                    fake.ready = false
                    fake.connection = BillingClient.ConnectionState.DISCONNECTED
                    wrapper.onBillingServiceDisconnected()
                    advanceTimeBy(RECONNECT_TIMER_MAX_TIME_MILLISECONDS + 1)
                    runCurrent()

                    Then("no reconnect is scheduled") {
                        assertEquals(connectionsBefore, fake.startConnectionCalls())
                    }
                }
            }
        }

    @Test
    fun `no second connection is started while one is in flight`() =
        runTest {
            Given("a billing client that is already connecting") {
                val fake = FakeClient().apply { connection = BillingClient.ConnectionState.CONNECTING }

                When("the wrapper starts up and a request comes in") {
                    val wrapper = makeWrapper(fake)
                    runCurrent()
                    backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                    runCurrent()

                    Then("the in-flight connection is left to finish") {
                        assertEquals(0, fake.startConnectionCalls())
                    }
                }
            }
        }

    @Test
    fun `developer error is ignored while a connection is in flight`() =
        runTest {
            Given("a request queued behind a connection that is still connecting") {
                val fake = FakeClient()
                val wrapper = makeWrapper(fake)
                runCurrent()
                backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                runCurrent()
                fake.connection = BillingClient.ConnectionState.CONNECTING
                val connectionsBefore = fake.startConnectionCalls()

                When("a duplicate startConnection is reported as DEVELOPER_ERROR") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR))
                    advanceTimeBy(RECONNECT_TIMER_MAX_TIME_MILLISECONDS + 1)
                    runCurrent()

                    Then("nothing extra is started") {
                        assertEquals(connectionsBefore, fake.startConnectionCalls())
                    }
                }
            }
        }

    @Test
    fun `developer error after the connection dropped reconnects for queued requests`() =
        runTest {
            Given("a request queued on a client whose connection has dropped") {
                val fake = FakeClient()
                val wrapper = makeWrapper(fake)
                runCurrent()
                val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                runCurrent()
                val connectionsBefore = fake.startConnectionCalls()

                When("a stale DEVELOPER_ERROR arrives for a connection that is no longer in flight") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR))
                    advanceTimeBy(RECONNECT_TIMER_START_MILLISECONDS + 1)
                    runCurrent()

                    Then("a reconnect is scheduled so the request can run") {
                        assertEquals(connectionsBefore + 1, fake.startConnectionCalls())
                        assertFalse(job.isCompleted)
                    }
                }
            }
        }
}
