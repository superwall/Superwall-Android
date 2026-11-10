package com.superwall.sdk.paywall.view.webview

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.paywall.view.webview.PaywallLoadRetrier.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallLoadRetrierTest {
    private val url = "https://example.com/paywall"
    private val reloads = mutableListOf<String>()

    private fun TestScope.retrier() = PaywallLoadRetrier(scope = backgroundScope) { reloads += it }

    @Test
    fun `a failed load is reloaded after a wait that doubles each time`() =
        runTest {
            Given("a load that keeps failing") {
                val retrier = retrier()

                val delays =
                    When("each reload fails in turn") {
                        (1..3).map {
                            val outcome = retrier.onLoadFailed(url)
                            advanceUntilIdle()
                            outcome
                        }
                    }

                Then("the waits are 0.5s, 1s and 2s, and each reloads once") {
                    assertEquals(
                        listOf<Outcome>(Outcome.Scheduled(500), Outcome.Scheduled(1000), Outcome.Scheduled(2000)),
                        delays,
                    )
                    assertEquals(listOf(url, url, url), reloads)
                }
            }
        }

    @Test
    fun `the page is not reloaded before the wait is over`() =
        runTest {
            Given("a failed load") {
                val retrier = retrier()
                retrier.onLoadFailed(url)

                When("less than the wait has passed") {
                    advanceTimeBy(499)
                    runCurrent()
                    Then("nothing is reloaded") { assertEquals(emptyList<String>(), reloads) }
                }

                When("the wait is over") {
                    advanceTimeBy(1)
                    runCurrent()
                    Then("the page is reloaded") { assertEquals(listOf(url), reloads) }
                }
            }
        }

    @Test
    fun `a load stops being reloaded after three reloads`() =
        runTest {
            Given("a load that has been reloaded three times") {
                val retrier = retrier()
                repeat(3) {
                    retrier.onLoadFailed(url)
                    advanceUntilIdle()
                }

                val outcome = When("it fails again") { retrier.onLoadFailed(url) }

                Then("it is out of reloads and nothing more is scheduled") {
                    assertEquals(Outcome.Exhausted, outcome)
                    advanceUntilIdle()
                    assertEquals(3, reloads.size)
                }
            }
        }

    @Test
    fun `errors that arrive while a reload is waiting do not schedule another`() =
        runTest {
            Given("a failed load with a reload waiting") {
                val retrier = retrier()
                retrier.onLoadFailed(url)

                val outcomes = When("the same load reports two more errors") { List(2) { retrier.onLoadFailed(url) } }

                Then("they are covered by the waiting reload and use up no reloads") {
                    assertEquals(listOf<Outcome>(Outcome.AlreadyScheduled, Outcome.AlreadyScheduled), outcomes)
                    advanceUntilIdle()
                    assertEquals(listOf(url), reloads)
                    assertEquals(Outcome.Scheduled(1000), retrier.onLoadFailed(url))
                }
            }
        }

    @Test
    fun `a new load gets its reloads back and drops a waiting reload`() =
        runTest {
            Given("a load that is out of reloads") {
                val retrier = retrier()
                repeat(3) {
                    retrier.onLoadFailed(url)
                    advanceUntilIdle()
                }
                reloads.clear()

                When("a new load starts and fails") {
                    retrier.reset()
                    val outcome = retrier.onLoadFailed(url)

                    Then("it starts again from the first wait") {
                        assertEquals(Outcome.Scheduled(500), outcome)
                    }
                }

                When("another load starts while that reload is waiting") {
                    retrier.reset()
                    advanceUntilIdle()

                    Then("the waiting reload never runs") {
                        assertEquals(emptyList<String>(), reloads)
                    }
                }
            }
        }

    @Test
    fun `cancelling drops a waiting reload without restoring reloads`() =
        runTest {
            Given("a failed load with a reload waiting") {
                val retrier = retrier()
                retrier.onLoadFailed(url)

                When("the view is destroyed") {
                    retrier.cancel()
                    advanceUntilIdle()

                    Then("the page is not reloaded") {
                        assertEquals(emptyList<String>(), reloads)
                    }
                    Then("the reload it used stays used") {
                        assertEquals(Outcome.Scheduled(1000), retrier.onLoadFailed(url))
                    }
                }
            }
        }
}
