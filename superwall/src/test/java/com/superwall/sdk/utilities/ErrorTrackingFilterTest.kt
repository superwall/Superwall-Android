package com.superwall.sdk.utilities

import com.superwall.sdk.billing.BillingError
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTrackingFilterTest {
    @Test
    fun billingErrorsAreNotLogged() {
        assertFalse(BillingError.BillingNotAvailable("billing unavailable").shouldLog())
        assertFalse(BillingError.WithCode(6, "transient billing error").shouldLog())
    }

    @Test
    fun presentationStatusReasonsAreNotLogged() {
        assertFalse(PaywallPresentationRequestStatusReason.NoConfig().shouldLog())
    }

    @Test
    fun unexpectedExceptionsAreLogged() {
        assertTrue(IllegalStateException("unexpected").shouldLog())
    }
}
