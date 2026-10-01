@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import androidx.lifecycle.LifecycleOwner
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.IOScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleBillingWrapperAvailabilityTest {
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

    private fun disconnectedClient(): BillingClient =
        mockk(relaxed = true) {
            every { isReady } returns false
        }

    private fun TestScope.makeWrapper(createBillingClient: () -> BillingClient): GoogleBillingWrapper {
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
            createBillingClient = { createBillingClient() },
        )
    }

    private fun TestScope.makeUnavailableWrapper(client: BillingClient): GoogleBillingWrapper {
        val wrapper = makeWrapper { client }
        runCurrent()
        wrapper.onBillingSetupFinished(
            billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
        )
        runCurrent()
        return wrapper
    }

    @Test
    fun `availability is unknown until the first connection attempt resolves`() =
        runTest {
            Given("a wrapper whose billing client hasn't finished setup") {
                val wrapper = makeWrapper { disconnectedClient() }
                runCurrent()

                Then("availability is unknown") {
                    assertEquals(BillingAvailability.Unknown, wrapper.availability.value)
                }

                When("setup finishes OK") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
                    runCurrent()

                    Then("billing is available") {
                        assertEquals(BillingAvailability.Available, wrapper.availability.value)
                    }
                }
            }
        }

    @Test
    fun `requests fail fast without reconnecting once billing is unavailable`() =
        runTest {
            Given("a device where billing setup reported BILLING_UNAVAILABLE") {
                val client = disconnectedClient()
                val wrapper = makeUnavailableWrapper(client)

                Then("availability is unavailable") {
                    assertTrue(wrapper.availability.value is BillingAvailability.Unavailable)
                }

                When("products are requested") {
                    val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                    runCurrent()

                    Then("the request fails with BillingNotAvailable") {
                        assertTrue(job.isCompleted)
                        assertTrue(job.await().exceptionOrNull() is BillingError.BillingNotAvailable)
                    }

                    And("the billing client was not asked to connect again") {
                        verify(exactly = 1) { client.startConnection(any()) }
                    }
                }
            }
        }

    @Test
    fun `requests fail instead of hanging when the billing client can't be created`() =
        runTest {
            Given("a device where creating the billing client throws") {
                val wrapper = makeWrapper { throw IllegalStateException("No Play Store") }
                runCurrent()

                Then("availability is unavailable") {
                    assertTrue(wrapper.availability.value is BillingAvailability.Unavailable)
                }

                When("products are requested") {
                    val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                    runCurrent()

                    Then("the request fails with BillingNotAvailable") {
                        assertTrue(job.isCompleted)
                        assertTrue(job.await().exceptionOrNull() is BillingError.BillingNotAvailable)
                    }
                }
            }
        }

    @Test
    fun `requests queued before the billing client fails to create are failed`() =
        runTest {
            Given("a billing client that fails to create after a request was queued") {
                var shouldThrow = false
                val wrapper =
                    makeWrapper {
                        if (shouldThrow) throw IllegalStateException("No Play Store")
                        disconnectedClient()
                    }
                runCurrent()
                wrapper.billingClient = null
                shouldThrow = true

                When("products are requested and the connection is retried") {
                    val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                    runCurrent()
                    wrapper.startConnection()
                    runCurrent()

                    Then("the queued request fails with BillingNotAvailable") {
                        assertTrue(job.isCompleted)
                        assertTrue(job.await().exceptionOrNull() is BillingError.BillingNotAvailable)
                    }
                }
            }
        }

    @Test
    fun `purchases query returns empty without retrying once billing is unavailable`() =
        runTest {
            Given("a device where billing is unavailable") {
                val wrapper = makeUnavailableWrapper(disconnectedClient())

                When("all purchases are queried") {
                    val purchases = wrapper.queryAllPurchases()

                    Then("it returns empty without waiting on retries") {
                        assertTrue(purchases.isEmpty())
                        assertEquals(0L, testScheduler.currentTime)
                    }
                }
            }
        }

    @Test
    fun `billing is probed again when the app returns to the foreground`() =
        runTest {
            Given("a device where billing is unavailable") {
                val client = disconnectedClient()
                val wrapper = makeUnavailableWrapper(client)
                val owner = mockk<LifecycleOwner>(relaxed = true)

                When("the app comes back to the foreground") {
                    lifecycleObserver.onStop(owner)
                    lifecycleObserver.onStart(owner)
                    runCurrent()

                    Then("the billing client is asked to connect again") {
                        verify(exactly = 2) { client.startConnection(any()) }
                        assertEquals(BillingAvailability.Unknown, wrapper.availability.value)
                    }

                    And("a successful setup makes billing available") {
                        wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
                        runCurrent()
                        assertEquals(BillingAvailability.Available, wrapper.availability.value)
                    }
                }
            }
        }

    @Test
    fun `a failed product load is not remembered once billing becomes available`() =
        runTest {
            Given("a product load that failed while billing was unavailable") {
                val client = disconnectedClient()
                val wrapper = makeUnavailableWrapper(client)
                val failed = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                runCurrent()
                assertTrue(failed.await().isFailure)

                When("billing becomes available and the product is requested again") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
                    runCurrent()
                    val retry = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                    runCurrent()

                    Then("the request goes to billing instead of failing from cache") {
                        assertTrue(!retry.isCompleted)
                        verify(atLeast = 2) { client.startConnection(any()) }
                    }
                }
            }
        }

    @Test
    fun `repeated transient setup failures mark billing unavailable and fail waiting requests`() =
        runTest {
            Given("a device whose billing setup keeps failing with ERROR") {
                val wrapper = makeWrapper { disconnectedClient() }
                runCurrent()
                val job = backgroundScope.async { runCatching { wrapper.awaitGetProducts(setOf(productId)) } }
                runCurrent()

                When("setup fails fewer times than the limit") {
                    repeat(MAX_TRANSIENT_SETUP_FAILURES - 1) {
                        wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.ERROR))
                    }
                    runCurrent()

                    Then("billing is still treated as transient and the request keeps waiting") {
                        assertEquals(BillingAvailability.Unknown, wrapper.availability.value)
                        assertTrue(!job.isCompleted)
                    }
                }

                When("setup fails once more") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.ERROR))
                    runCurrent()

                    Then("billing is unavailable and the waiting request fails") {
                        assertTrue(wrapper.availability.value is BillingAvailability.Unavailable)
                        assertTrue(job.isCompleted)
                        assertTrue(job.await().exceptionOrNull() is BillingError.BillingNotAvailable)
                    }
                }

                When("a later reconnect succeeds") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
                    runCurrent()

                    Then("billing is available again") {
                        assertEquals(BillingAvailability.Available, wrapper.availability.value)
                    }
                }
            }
        }

    @Test
    fun `a successful setup resets the transient failure count`() =
        runTest {
            Given("a device whose billing setup fails transiently, then connects") {
                val wrapper = makeWrapper { disconnectedClient() }
                runCurrent()
                repeat(MAX_TRANSIENT_SETUP_FAILURES - 1) {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.ERROR))
                }
                wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
                runCurrent()

                When("setup later fails transiently again") {
                    wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.ERROR))
                    runCurrent()

                    Then("billing is not marked unavailable") {
                        assertEquals(BillingAvailability.Available, wrapper.availability.value)
                    }
                }
            }
        }
}
