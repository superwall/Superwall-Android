package com.superwall.sdk.web

import com.superwall.sdk.models.internal.RedemptionResult.PaywallInfo.PaywallProduct
import org.junit.Assert.*
import org.junit.Test

class WebTrialReminderTest {
    private val checkout = 1788784200000L
    private val day = 86_400_000L
    private val product = PaywallProduct("web", trialPeriodDays = 7, trialPeriodEndDate = "2026-09-14T12:30:00Z")

    @Test
    fun `late redemption subtracts elapsed time from reminder delay`() {
        assertEquals(5 * day, webTrialReminderDelay(product, 6 * day, checkout + day))
        assertEquals(6 * day, webTrialReminderDelay(product, 6 * day, checkout))
    }

    @Test
    fun `offset timestamps refer to the same instant`() {
        assertEquals(
            5 * day,
            webTrialReminderDelay(product.copy(trialPeriodEndDate = "2026-09-14T14:30:00+02:00"), 6 * day, checkout + day),
        )
    }

    @Test
    fun `past reminders and reminders at or after conversion are skipped`() {
        assertNull(webTrialReminderDelay(product, day, checkout + 2 * day))
        assertNull(webTrialReminderDelay(product, 7 * day, checkout))
        assertNull(webTrialReminderDelay(product, 8 * day, checkout))
        assertNull(webTrialReminderDelay(product, 6 * day, checkout + 8 * day))
    }

    @Test
    fun `ambiguous invalid or overflowing dates are safe to skip`() {
        for (end in listOf("", "2026-09-14", "September 14, 2026", "invalid", "+999999999-09-14T12:30:00Z")) {
            assertNull(webTrialReminderDelay(product.copy(trialPeriodEndDate = end), day, checkout))
        }
        assertNull(webTrialReminderDelay(product, Long.MAX_VALUE, checkout))
    }
}
