@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.ConnectionState.Updates
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    private val unavailable = BillingError.BillingNotAvailable("no play store")

    @Test
    fun `a new state waits for its first connection`() {
        val state = ConnectionState()
        assertEquals(BillingAvailability.Unknown, state.availability)
        assertEquals(RECONNECT_TIMER_START_MILLISECONDS, state.backoffMs)
        assertEquals(ReconnectSchedule.Idle, state.retry)
        assertEquals(0, state.failures)
        assertTrue(state.pendingRequests.isEmpty())
        assertFalse(state.isReady)
    }

    @Test
    fun `isReady follows the client`() {
        val client = mockk<BillingClient> { every { isReady } returns true }
        assertTrue(Updates.ClientCreated(client).reduce(ConnectionState()).isReady)
    }

    @Test
    fun `requests queue in order and are taken together`() {
        Given("two requests queued one after the other") {
            val first = PendingRequest(null) {}
            val second = PendingRequest(500L) {}
            val queued =
                Updates.Enqueue(second).reduce(Updates.Enqueue(first).reduce(ConnectionState()))

            Then("they keep their order") {
                assertSame(first, queued.pendingRequests[0])
                assertSame(second, queued.pendingRequests[1])
            }

            When("they are taken") {
                val taken = Updates.RequestsTaken.reduce(queued)

                Then("the queue is empty") {
                    assertTrue(taken.pendingRequests.isEmpty())
                }
            }
        }
    }

    @Test
    fun `the backoff doubles up to its maximum and resets on connect`() {
        Given("a reconnect scheduled five times") {
            var state = ConnectionState()
            val seen = mutableListOf<Long>()
            repeat(5) {
                state = Updates.ReconnectScheduled.reduce(state)
                seen += state.backoffMs
            }

            Then("each wait doubles until 16 seconds") {
                assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 16_000L), seen)
                assertEquals(ReconnectSchedule.Scheduled, state.retry)
            }

            When("a connection attempt starts and then succeeds") {
                val connected =
                    Updates.Connected.reduce(Updates.ConnectionStarted.reduce(state))

                Then("billing is available and the backoff starts over") {
                    assertEquals(BillingAvailability.Available, connected.availability)
                    assertEquals(RECONNECT_TIMER_START_MILLISECONDS, connected.backoffMs)
                    assertEquals(ReconnectSchedule.Idle, connected.retry)
                    assertEquals(0, connected.failures)
                }
            }
        }
    }

    @Test
    fun `three transient setup failures in a row make billing unavailable`() {
        Given("a state with no failures yet") {
            var state = ConnectionState()

            When("setup fails twice") {
                repeat(2) { state = Updates.TransientSetupFailure("boom", 6).reduce(state) }

                Then("billing is still not unavailable") {
                    assertEquals(2, state.failures)
                    assertEquals(BillingAvailability.Unknown, state.availability)
                }
            }

            When("it fails a third time") {
                state = Updates.TransientSetupFailure("boom", 6).reduce(state)

                Then("billing is unavailable with the last error") {
                    val availability = state.availability as BillingAvailability.Unavailable
                    assertEquals(
                        "Billing is not available in this device. Setup failed 3 times in a row. " +
                            "Last error: boom ErrorCode: 6.",
                        availability.error.message,
                    )
                }
            }
        }
    }

    @Test
    fun `a failure after billing is unavailable keeps the first error`() {
        val state =
            Updates
                .TransientSetupFailure("later", 6)
                .reduce(Updates.MarkUnavailable(unavailable).reduce(ConnectionState(failures = 5)))
        assertEquals(BillingAvailability.Unavailable(unavailable), state.availability)
        assertEquals(6, state.failures)
    }

    @Test
    fun `a re-probe forgets unavailability and past failures`() {
        val state =
            Updates.Reprobe.reduce(
                Updates.MarkUnavailable(unavailable).reduce(ConnectionState(failures = 3)),
            )
        assertEquals(BillingAvailability.Unknown, state.availability)
        assertEquals(0, state.failures)
    }
}
