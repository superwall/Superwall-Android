@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingConnectionTest {
    private fun result(code: Int): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage("")
            .build()

    /** A billing client whose readiness the test controls. */
    private class FakeClient {
        var ready = false
        var connection = BillingClient.ConnectionState.DISCONNECTED
        var startConnections = 0
        val client: BillingClient =
            mockk(relaxed = true) {
                every { isReady } answers { this@FakeClient.ready }
                every { connectionState } answers { this@FakeClient.connection }
                every { startConnection(any()) } answers { this@FakeClient.startConnections++ }
            }

        fun connect() {
            ready = true
            connection = BillingClient.ConnectionState.CONNECTED
        }
    }

    private fun TestScope.connection(createClient: () -> BillingClient): BillingConnection =
        BillingConnection(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            createBillingClient = { createClient() },
            purchasesListener = mockk(relaxed = true),
            stateListener = mockk<BillingClientStateListener>(relaxed = true),
        )

    @Test
    fun `queued requests run in the order they were queued once connected`() =
        runTest {
            Given("a connection with three requests waiting") {
                val fake = FakeClient()
                val connection = connection { fake.client }
                connection.connect()
                runCurrent()
                val ran = mutableListOf<Pair<Int, BillingError?>>()
                (1..3).forEach { n -> connection.request { ran += n to it } }
                runCurrent()

                Then("nothing runs while the client isn't ready") {
                    assertTrue(ran.isEmpty())
                    assertEquals(3, connection.state.value.pendingRequests.size)
                }

                When("setup finishes OK") {
                    fake.connect()
                    connection.setupFinished(result(BillingClient.BillingResponseCode.OK))
                    runCurrent()

                    Then("all three run, oldest first, without an error") {
                        assertEquals(listOf(1 to null, 2 to null, 3 to null), ran)
                    }

                    And("billing is available and the queue is empty") {
                        assertEquals(BillingAvailability.Available, connection.availability.value)
                        assertTrue(connection.state.value.pendingRequests.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `billing unavailable fails queued requests in order and later ones straight away`() =
        runTest {
            Given("two requests waiting on a connection") {
                val fake = FakeClient()
                val connection = connection { fake.client }
                connection.connect()
                runCurrent()
                val ran = mutableListOf<Pair<Int, BillingError?>>()
                connection.request { ran += 1 to it }
                connection.request { ran += 2 to it }
                runCurrent()

                When("setup reports BILLING_UNAVAILABLE") {
                    connection.setupFinished(result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
                    runCurrent()

                    Then("both fail with BillingNotAvailable, oldest first") {
                        assertEquals(listOf(1, 2), ran.map { it.first })
                        assertTrue(ran.all { it.second is BillingError.BillingNotAvailable })
                    }

                    And("a new request fails without queueing or reconnecting") {
                        val startsBefore = fake.startConnections
                        var error: BillingError? = null
                        connection.request { error = it }
                        runCurrent()
                        assertTrue(error is BillingError.BillingNotAvailable)
                        assertTrue(connection.state.value.pendingRequests.isEmpty())
                        assertEquals(startsBefore, fake.startConnections)
                    }
                }
            }
        }

    @Test
    fun `transient failures back off and give up after the limit`() =
        runTest {
            Given("a request waiting on a connection whose setup keeps failing") {
                val fake = FakeClient()
                val connection = connection { fake.client }
                connection.connect()
                runCurrent()
                var error: BillingError? = null
                var called = false
                connection.request {
                    called = true
                    error = it
                }
                runCurrent()

                When("one setup fails") {
                    val startsBefore = fake.startConnections
                    connection.setupFinished(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
                    runCurrent()

                    Then("a reconnect waits for the starting backoff, and the next one waits twice as long") {
                        assertEquals(ReconnectSchedule.Scheduled, connection.state.value.retry)
                        assertEquals(RECONNECT_TIMER_START_MILLISECONDS * 2, connection.state.value.backoffMs)
                        advanceTimeBy(RECONNECT_TIMER_START_MILLISECONDS - 1)
                        runCurrent()
                        assertEquals(startsBefore, fake.startConnections)
                        advanceTimeBy(2)
                        runCurrent()
                        assertEquals(startsBefore + 1, fake.startConnections)
                        assertEquals(ReconnectSchedule.Idle, connection.state.value.retry)
                    }
                }

                When("setup fails until the limit is reached") {
                    repeat(MAX_TRANSIENT_SETUP_FAILURES - 1) {
                        connection.setupFinished(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
                        runCurrent()
                    }

                    Then("billing is unavailable and the waiting request fails") {
                        assertTrue(connection.availability.value is BillingAvailability.Unavailable)
                        assertTrue(called)
                        assertTrue(error is BillingError.BillingNotAvailable)
                    }
                }
            }
        }

    @Test
    fun `a second failure while a reconnect is scheduled doesn't schedule another`() =
        runTest {
            Given("a reconnect already scheduled for a waiting request") {
                val fake = FakeClient()
                val connection = connection { fake.client }
                connection.connect()
                runCurrent()
                connection.request { }
                runCurrent()
                connection.disconnected()
                runCurrent()
                val backoff = connection.state.value.backoffMs
                val startsBefore = fake.startConnections

                When("the client disconnects again before it runs") {
                    connection.disconnected()
                    advanceTimeBy(RECONNECT_TIMER_MAX_TIME_MILLISECONDS + 1)
                    runCurrent()

                    Then("only one reconnect runs and the backoff doubled once") {
                        assertEquals(startsBefore + 1, fake.startConnections)
                        assertEquals(backoff, connection.state.value.backoffMs)
                    }
                }
            }
        }

    @Test
    fun `awaiting a connection asks Play again even while billing is unavailable`() =
        runTest {
            Given("a connection marked unavailable") {
                val fake = FakeClient()
                val connection = connection { fake.client }
                connection.connect()
                runCurrent()
                connection.setupFinished(result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
                runCurrent()
                val startsBefore = fake.startConnections

                When("a caller awaits the connection") {
                    val awaited = backgroundScope.async { connection.awaitConnection(timeoutMs = 5_000) }
                    runCurrent()

                    Then("a connection is started instead of failing from the cached state") {
                        assertEquals(startsBefore + 1, fake.startConnections)
                        assertFalse(awaited.isCompleted)
                    }

                    // A connection that succeeds after waiting used to come back as "timed out",
                    // because a null error and a timeout were the same null.
                    And("it succeeds once setup does") {
                        fake.connect()
                        connection.setupFinished(result(BillingClient.BillingResponseCode.OK))
                        runCurrent()
                        assertTrue(awaited.await().isSuccess)
                    }
                }
            }
        }

    @Test
    fun `awaiting a connection fails when Play never answers`() =
        runTest {
            Given("a client whose setup never calls back") {
                val fake = FakeClient()
                val connection = connection { fake.client }

                When("a caller awaits the connection past the timeout") {
                    val awaited = backgroundScope.async { connection.awaitConnection(timeoutMs = 5_000) }
                    advanceTimeBy(5_001)
                    runCurrent()

                    Then("it fails with a timeout") {
                        assertEquals("Billing connection timed out", awaited.await().exceptionOrNull()?.message)
                    }
                }
            }
        }

    @Test
    fun `a client that can't be created makes billing unavailable`() =
        runTest {
            Given("a device where creating the client throws") {
                val connection = connection { throw IllegalStateException("No Play Store") }

                When("a connection is started") {
                    connection.connect()
                    runCurrent()

                    Then("billing is unavailable and there's no client") {
                        assertTrue(connection.availability.value is BillingAvailability.Unavailable)
                        assertNull(connection.client)
                    }
                }
            }
        }

    @Test
    fun `reprobing only reconnects when billing was unavailable`() =
        runTest {
            Given("a connected client") {
                val fake = FakeClient()
                val connection = connection { fake.client }
                connection.connect()
                runCurrent()
                fake.connect()
                connection.setupFinished(result(BillingClient.BillingResponseCode.OK))
                runCurrent()
                val startsBefore = fake.startConnections

                When("the app returns to the foreground") {
                    connection.reprobe()
                    runCurrent()

                    Then("nothing changes") {
                        assertEquals(BillingAvailability.Available, connection.availability.value)
                        assertEquals(startsBefore, fake.startConnections)
                    }
                }

                When("billing became unavailable and the app returns to the foreground") {
                    fake.ready = false
                    fake.connection = BillingClient.ConnectionState.DISCONNECTED
                    connection.setupFinished(result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
                    runCurrent()
                    connection.reprobe()
                    runCurrent()

                    Then("billing is probed again from scratch") {
                        assertEquals(BillingAvailability.Unknown, connection.availability.value)
                        assertEquals(0, connection.state.value.failures)
                        assertEquals(startsBefore + 1, fake.startConnections)
                    }
                }
            }
        }

    @Test
    fun `setup results map to the right outcome`() {
        assertEquals(SetupOutcome.Connected, SetupOutcome.from(result(BillingClient.BillingResponseCode.OK)))
        assertTrue(SetupOutcome.from(result(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)) is SetupOutcome.Unavailable)
        assertEquals(SetupOutcome.Transient, SetupOutcome.from(result(BillingClient.BillingResponseCode.NETWORK_ERROR)))
        assertEquals(SetupOutcome.ItemError, SetupOutcome.from(result(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)))
        assertEquals(SetupOutcome.DuplicateConnection, SetupOutcome.from(result(BillingClient.BillingResponseCode.DEVELOPER_ERROR)))
        val legacy =
            SetupOutcome.from(
                BillingResult
                    .newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
                    .setDebugMessage(IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE)
                    .build(),
            ) as SetupOutcome.Unavailable
        assertTrue(legacy.error.message!!.contains("Make sure there's an account configured in Play Store"))
    }
}
