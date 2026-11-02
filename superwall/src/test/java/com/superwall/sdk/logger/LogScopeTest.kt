@file:Suppress("DEPRECATION")

package com.superwall.sdk.logger

import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.assertFalse
import com.superwall.sdk.assertTrue
import com.superwall.sdk.config.options.SuperwallOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Test
import java.util.EnumSet
import kotlin.test.assertEquals

class LogScopeTest {
    @Test
    fun `billing logs are enabled by the billing scope`() {
        Given("logging scopes that contain billing") {
            val scopes = EnumSet.of(LogScope.billing)

            Then("billing logs are enabled") {
                assertTrue(LogScope.billing.isEnabledBy(scopes))
            }
        }
    }

    @Test
    fun `billing logs are still enabled by the deprecated storeKitManager scope`() {
        Given("logging scopes set to the deprecated storeKitManager only") {
            val scopes = EnumSet.of(LogScope.storeKitManager)

            Then("billing logs are enabled") {
                assertTrue(LogScope.billing.isEnabledBy(scopes))
            }
            Then("other scopes stay disabled") {
                assertFalse(LogScope.paywallView.isEnabledBy(scopes))
                assertFalse(LogScope.network.isEnabledBy(scopes))
            }
        }
    }

    @Test
    fun `all enables every scope`() {
        Given("logging scopes set to all") {
            val scopes = EnumSet.of(LogScope.all)

            Then("every scope is enabled") {
                LogScope.entries.forEach { assertTrue(it.isEnabledBy(scopes)) }
            }
        }
    }

    @Test
    fun `a scope outside the set is disabled`() {
        Given("logging scopes set to network only") {
            val scopes = EnumSet.of(LogScope.network)

            Then("billing logs are disabled") {
                assertFalse(LogScope.billing.isEnabledBy(scopes))
            }
        }
    }

    @Test
    fun `existing enum values keep their ordinals`() {
        Given("the scopes as they were before billing was added") {
            Then("all keeps its position and billing is appended after it") {
                assertEquals(28, LogScope.all.ordinal)
                assertEquals(LogScope.all.ordinal + 1, LogScope.billing.ordinal)
                assertEquals(LogScope.entries.last(), LogScope.billing)
            }
        }
    }

    @Test
    fun `configured storeKitManager scope prints billing logs`() {
        val options =
            SuperwallOptions().apply {
                logging =
                    SuperwallOptions.Logging().apply {
                        level = LogLevel.debug
                        scopes = EnumSet.of(LogScope.storeKitManager)
                    }
            }
        val superwall = mockk<Superwall>()
        every { superwall.options } returns options
        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns superwall
        Superwall.initialized = true

        try {
            Given("Superwall configured with only the deprecated storeKitManager scope") {
                When("checking whether billing and paywall logs print") {
                    val billing = Loggable.shouldPrint(LogLevel.debug, LogScope.billing)
                    val paywall = Loggable.shouldPrint(LogLevel.debug, LogScope.paywallView)

                    Then("billing logs print and paywall logs do not") {
                        assertTrue(billing)
                        assertFalse(paywall)
                    }
                }
            }
        } finally {
            Superwall.initialized = false
            unmockkObject(Superwall.Companion)
        }
    }
}
