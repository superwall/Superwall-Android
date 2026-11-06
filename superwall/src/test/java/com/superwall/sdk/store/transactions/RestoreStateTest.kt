package com.superwall.sdk.store.transactions

import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreStateTest {
    private val first = mockk<PaywallInfo>()
    private val second = mockk<PaywallInfo>()

    @Test
    fun `begin and finish count overlapping restores`() {
        val one = RestoreState.Updates.Begin(first).reduce(RestoreState.Idle)
        val two = RestoreState.Updates.Begin(second).reduce(one)
        assertEquals(RestoreState.Restoring(second, running = 2), two)

        val afterOne = RestoreState.Updates.Finish.reduce(two)
        assertEquals(RestoreState.Restoring(second, running = 1), afterOne)
        assertEquals(RestoreState.Idle, RestoreState.Updates.Finish.reduce(afterOne))
        assertEquals(RestoreState.Idle, RestoreState.Updates.Finish.reduce(RestoreState.Idle))
    }

    private val pro = Entitlement("pro")

    private fun outcome(
        result: RestorationResult = RestorationResult.Restored(),
        status: SubscriptionStatus = SubscriptionStatus.Inactive,
        web: Set<Entitlement> = emptySet(),
        paywall: List<Entitlement> = listOf(pro),
        webToApp: Boolean = false,
    ) = RestoreOutcome.of(result, status, web, paywall, webToApp)

    @Test
    fun `restored with the paywall's entitlement is restored`() {
        assertEquals(RestoreOutcome.Restored, outcome(status = SubscriptionStatus.Active(setOf(pro)), webToApp = true))
    }

    @Test
    fun `web entitlements count as restored`() {
        assertEquals(RestoreOutcome.Restored, outcome(web = setOf(pro), webToApp = true))
    }

    @Test
    fun `restored but missing the paywall's entitlement asks about the web only when web checkout is on`() {
        val active = SubscriptionStatus.Active(setOf(Entitlement("basic")))
        assertEquals(RestoreOutcome.MissingWebEntitlements, outcome(status = active, webToApp = true))
        assertEquals(RestoreOutcome.Restored, outcome(status = active, webToApp = false))
    }

    @Test
    fun `nothing to restore fails and offers the web only when web checkout is on`() {
        val failed = outcome(webToApp = true)
        assertTrue(failed is RestoreOutcome.Failed && failed.offerWebRestore)
        val noWeb = outcome(result = RestorationResult.Failed(null), status = SubscriptionStatus.Active(setOf(pro)))
        assertTrue(noWeb is RestoreOutcome.Failed && !noWeb.offerWebRestore)
    }

    @Test
    fun `the web prompt is worded for whether Play restored anything`() {
        assertEquals("Restore via the web?", webRestorePrompt(true) {}.title)
        assertEquals("No Subscription Found", webRestorePrompt(false) {}.title)
    }
}
