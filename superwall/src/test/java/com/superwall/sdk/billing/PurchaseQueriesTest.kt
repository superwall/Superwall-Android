@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingConfig
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseQueriesTest {
    private val productId = "product1:basePlan1:sw-auto"

    private fun result(code: Int): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage("")
            .build()

    /** A connected billing client that records each query's listener so the test answers it. */
    private class FakeClient {
        var ready = true
        val productListeners = mutableListOf<ProductDetailsResponseListener>()
        val purchaseListeners = mutableListOf<PurchasesResponseListener>()
        val consumeListeners = mutableListOf<ConsumeResponseListener>()
        val configListeners = mutableListOf<BillingConfigResponseListener>()
        val client: BillingClient =
            mockk(relaxed = true) {
                every { isReady } answers { this@FakeClient.ready }
                every { connectionState } returns BillingClient.ConnectionState.CONNECTED
                every { queryProductDetailsAsync(any(), any()) } answers { productListeners += secondArg<ProductDetailsResponseListener>() }
                every { queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>(), any()) } answers {
                    purchaseListeners += secondArg<PurchasesResponseListener>()
                }
                every { consumeAsync(any(), any()) } answers { consumeListeners += secondArg<ConsumeResponseListener>() }
                every { getBillingConfigAsync(any(), any()) } answers { configListeners += secondArg<BillingConfigResponseListener>() }
            }
    }

    private fun TestScope.queries(fake: FakeClient): Pair<PurchaseQueries, BillingConnection> {
        val connection =
            BillingConnection(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                createBillingClient = { fake.client },
                purchasesListener = mockk(relaxed = true),
                stateListener = mockk(relaxed = true),
            )
        connection.connect()
        connection.setupFinished(result(BillingClient.BillingResponseCode.OK))
        runCurrent()
        return PurchaseQueries(connection, ProductDetailsRepository(), appInBackground = { false }) to connection
    }

    private fun FakeClient.answerProducts(code: Int) {
        productListeners.toList().forEach {
            it.onProductDetailsResponse(result(code), mockk<QueryProductDetailsResult>(relaxed = true))
        }
    }

    @Test
    fun `products queries subscriptions and one-time products and caches what Play returned`() =
        runTest {
            Given("connected queries") {
                val fake = FakeClient()
                val (queries) = queries(fake)

                When("products are requested and Play doesn't know the product") {
                    val first = backgroundScope.async { runCatching { queries.products(setOf(productId)) } }
                    runCurrent()

                    Then("both product types are queried") {
                        assertEquals(2, fake.productListeners.size)
                    }

                    fake.answerProducts(BillingClient.BillingResponseCode.OK)
                    runCurrent()

                    Then("the request finishes with no products") {
                        assertTrue(first.await().getOrThrow().isEmpty())
                    }

                    And("asking again fails from the cache without querying Play") {
                        val second = runCatching { queries.products(setOf(productId)) }
                        assertTrue(second.isFailure)
                        assertEquals(2, fake.productListeners.size)
                    }
                }
            }
        }

    @Test
    fun `a products result lists what Play didn't return as missing instead of failing`() =
        runTest {
            Given("connected queries") {
                val fake = FakeClient()
                val (queries) = queries(fake)

                When("a product Play doesn't know is requested") {
                    val first = backgroundScope.async { queries.productsResult(setOf(productId)) }
                    runCurrent()
                    fake.answerProducts(BillingClient.BillingResponseCode.OK)
                    runCurrent()

                    Then("the id is reported missing") {
                        val result = first.await()
                        assertTrue(result.found.isEmpty())
                        assertEquals(setOf(productId), result.missingIds)
                    }

                    And("asking again reports it missing without querying Play") {
                        assertEquals(setOf(productId), queries.productsResult(setOf(productId)).missingIds)
                        assertEquals(2, fake.productListeners.size)
                    }
                }
            }
        }

    @Test
    fun `an answer that arrives after the timeout still fills the cache`() =
        runTest {
            Given("a request whose answer comes too late") {
                val fake = FakeClient()
                val (queries) = queries(fake)
                val job = backgroundScope.async { runCatching { queries.products(setOf(productId)) } }
                runCurrent()
                advanceTimeBy(PRODUCTS_QUERY_TIMEOUT_MS + 1)
                runCurrent()

                Then("the request fails with a timeout") {
                    assertTrue(job.await().exceptionOrNull() is BillingError.Timeout)
                }

                When("Play answers afterwards") {
                    fake.answerProducts(BillingClient.BillingResponseCode.OK)
                    runCurrent()

                    Then("the next request is served from the cache, without a new query") {
                        val next = runCatching { queries.products(setOf(productId)) }
                        assertTrue(next.isFailure)
                        assertEquals(2, fake.productListeners.size)
                    }
                }
            }
        }

    @Test
    fun `a request Play never answers leaves no job behind once it times out`() =
        runTest {
            Given("several product requests Play never answers") {
                val fake = FakeClient()
                val (queries, connection) = queries(fake)
                val scopeJob = connection.scope.coroutineContext[Job]!!
                val before = scopeJob.children.count()

                When("each one times out") {
                    repeat(3) {
                        val job = backgroundScope.async { runCatching { queries.products(setOf(productId)) } }
                        runCurrent()
                        advanceTimeBy(PRODUCTS_QUERY_TIMEOUT_MS + 1)
                        runCurrent()
                        assertTrue(job.await().exceptionOrNull() is BillingError.Timeout)
                    }

                    Then("no fetch is left running in the connection's scope") {
                        assertEquals(before, scopeJob.children.count())
                        assertEquals(6, fake.productListeners.size)
                    }
                }
            }
        }

    @Test
    fun `a failed product type fails the request and nothing is cached`() =
        runTest {
            Given("a product request") {
                val fake = FakeClient()
                val (queries) = queries(fake)
                val job = backgroundScope.async { runCatching { queries.products(setOf(productId)) } }
                runCurrent()

                When("one product type fails") {
                    fake.productListeners.first().onProductDetailsResponse(
                        result(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE),
                        mockk(relaxed = true),
                    )
                    runCurrent()

                    Then("the request fails with that error") {
                        assertTrue(job.await().exceptionOrNull() is BillingError.WithCode)
                    }

                    And("the next request queries Play again") {
                        backgroundScope.async { runCatching { queries.products(setOf(productId)) } }
                        runCurrent()
                        assertEquals(4, fake.productListeners.size)
                    }
                }
            }
        }

    @Test
    fun `an OK answer after the other type failed doesn't throw in Play's listener`() =
        runTest {
            Given("a product request") {
                val fake = FakeClient()
                val (queries) = queries(fake)
                val job = backgroundScope.async { runCatching { queries.products(setOf(productId)) } }
                runCurrent()
                val (subs, inApp) = fake.productListeners.toList()

                When("subscriptions fail and one-time products then answer OK") {
                    subs.onProductDetailsResponse(
                        result(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE),
                        mockk(relaxed = true),
                    )
                    val thrown =
                        runCatching {
                            inApp.onProductDetailsResponse(
                                result(BillingClient.BillingResponseCode.OK),
                                mockk<QueryProductDetailsResult>(relaxed = true),
                            )
                        }.exceptionOrNull()
                    runCurrent()

                    Then("the OK callback returns normally") {
                        assertEquals(null, thrown)
                    }

                    And("the request fails with the subscription error") {
                        assertTrue(job.await().exceptionOrNull() is BillingError.WithCode)
                    }
                }
            }
        }

    @Test
    fun `queryType returns Play's purchases and fails when the client isn't ready`() =
        runTest {
            Given("connected queries") {
                val fake = FakeClient()
                val (queries) = queries(fake)
                val purchase = mockk<Purchase>()

                When("Play answers the purchase query") {
                    val job = backgroundScope.async { queries.queryType(BillingClient.ProductType.SUBS) }
                    runCurrent()
                    fake.purchaseListeners.single().onQueryPurchasesResponse(
                        result(BillingClient.BillingResponseCode.OK),
                        listOf(purchase),
                    )
                    runCurrent()

                    Then("its purchases are returned") {
                        assertEquals(listOf(purchase), job.await().getOrThrow())
                    }
                }

                When("the client is no longer ready") {
                    fake.ready = false

                    Then("the query fails without asking Play") {
                        assertTrue(queries.queryType(BillingClient.ProductType.SUBS).isFailure)
                        assertEquals(1, fake.purchaseListeners.size)
                    }
                }
            }
        }

    @Test
    fun `allPurchases is empty while billing is unavailable`() =
        runTest {
            Given("a connection whose setup reported billing unavailable") {
                val fake = FakeClient()
                val (queries, connection) = queries(fake)
                connection.setupFinished(result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
                runCurrent()

                Then("no purchases are returned and Play isn't asked") {
                    assertTrue(queries.allPurchases().isEmpty())
                    assertTrue(fake.purchaseListeners.isEmpty())
                }
            }
        }

    @Test
    fun `consume returns the token and the storefront country comes from the billing config`() =
        runTest {
            Given("connected queries") {
                val fake = FakeClient()
                val (queries) = queries(fake)

                When("a purchase is consumed") {
                    val job = backgroundScope.async { queries.consume("token") }
                    runCurrent()
                    fake.consumeListeners.single().onConsumeResponse(result(BillingClient.BillingResponseCode.OK), "token")
                    runCurrent()

                    Then("the consumed token is returned") {
                        assertEquals("token", job.await().getOrThrow())
                    }
                }

                When("the storefront country is requested") {
                    val job = backgroundScope.async { queries.storefrontCountryCode() }
                    runCurrent()
                    val config = mockk<BillingConfig> { every { countryCode } returns "HR" }
                    fake.configListeners.single().onBillingConfigResponse(result(BillingClient.BillingResponseCode.OK), config)
                    runCurrent()

                    Then("Play's country is returned") {
                        assertEquals("HR", job.await())
                    }
                }
            }
        }
}
