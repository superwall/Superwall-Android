package com.superwall.sdk.paywall.view.webview

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallRuntimeReadinessTest {
    @Test
    fun `a ready paywall is confirmed`() =
        Given("a page that is loading") {
            val readiness = PaywallRuntimeReadiness().apply { pageLoadStarted() }

            When("the paywall reports that it is ready") { readiness.readyReported() }

            Then("the runtime is confirmed") { assertTrue(readiness.confirmed) }
        }

    @Test
    fun `an optimistic ready is not a confirmation`() =
        Given("a failed page that finished, with optimistic loading") {
            val readiness = PaywallRuntimeReadiness().apply { pageLoadStarted() }

            When("the SDK marks the paywall ready because the page finished") {
                readiness.optimisticReadySent()
                readiness.readyReported()
            }

            Then("the runtime is not confirmed") { assertFalse(readiness.confirmed) }
        }

    @Test
    fun `a retry after an optimistic ready starts unconfirmed and can be confirmed`() =
        Given("a failed page that was optimistically marked ready") {
            val readiness = PaywallRuntimeReadiness().apply { pageLoadStarted() }
            readiness.optimisticReadySent()
            readiness.readyReported()

            When("the page is loaded again") { readiness.pageLoadStarted() }

            Then("it is unconfirmed until the paywall reports ready itself") {
                assertFalse(readiness.confirmed)
                readiness.optimisticReadySent()
                readiness.readyReported()
                assertFalse(readiness.confirmed)
                readiness.readyReported()
                assertTrue(readiness.confirmed)
            }
        }

    @Test
    fun `a new page load clears a confirmation`() =
        Given("a confirmed paywall") {
            val readiness = PaywallRuntimeReadiness().apply { readyReported() }

            When("a fallback URL or reload starts") { readiness.pageLoadStarted() }

            Then("the runtime is unconfirmed again") { assertFalse(readiness.confirmed) }
        }

    @Test
    fun `a real ready that overtakes the optimistic report still ends confirmed`() =
        Given("an optimistic ready whose report hasn't come back") {
            val readiness = PaywallRuntimeReadiness().apply { pageLoadStarted() }
            readiness.optimisticReadySent()

            When("the paywall's own ready is reported, then the optimistic one") {
                readiness.readyReported()
                readiness.readyReported()
            }

            Then("the runtime is confirmed") { assertTrue(readiness.confirmed) }
        }
}
