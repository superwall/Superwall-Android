package com.superwall.sdk.customercenter

import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.customercenter.CustomerCenterFixtures.customerInfo
import com.superwall.sdk.dependencies.DependencyContainer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerCenterManagerTest {
    private class Harness(
        launchThrows: Boolean = false,
    ) {
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()
        var dismissals = 0
        val manager =
            CustomerCenterManager(
                container =
                    mockk<DependencyContainer>(relaxed = true) {
                        every { context } returns ApplicationProvider.getApplicationContext()
                        every { activityProvider } returns null
                    },
                // The activity never starts, as when Android refuses a start from the background.
                launch = { _, _ -> if (launchThrows) throw SecurityException("Not allowed") },
                postDelayed = { delayMillis, action -> delayed.add(delayMillis to action) },
                makeDependencies = { _, _ ->
                    CustomerCenterDependencies(
                        FakeCustomerInfo(customerInfo()),
                        FakeProducts(),
                        FakeRestorer(),
                        FakeOpener(),
                        FakeTracker(),
                        FakeEnvironment(),
                    )
                },
            )

        fun present() = manager.present(CustomerCenterConfiguration.default, null) { dismissals += 1 }

        fun timeOut() = delayed.toList().forEach { (_, action) -> action() }
    }

    @Test
    fun `a presentation whose activity never starts ends after the timeout`() =
        Given("a presentation whose activity never appears") {
            val h = Harness()
            h.present()
            Then("it waits for the activity") {
                assertTrue(h.manager.isPresented)
                assertEquals(listOf(CustomerCenterManager.ATTACH_TIMEOUT_MS), h.delayed.map { it.first })
            }

            When("the timeout passes, twice over") {
                h.timeOut()
                h.timeOut()
            }
            Then("the presentation ends once, and a new one can start") {
                assertFalse(h.manager.isPresented)
                assertEquals(1, h.dismissals)
                h.present()
                assertTrue(h.manager.isPresented)
            }
        }

    @Test
    fun `a presentation whose activity attached is left alone`() =
        Given("a presentation whose activity was created") {
            val h = Harness()
            h.present()
            h.manager.sessionAttached(h.manager.session!!)

            When("the timeout passes") { h.timeOut() }
            Then("it's still presented and nothing was dismissed") {
                assertTrue(h.manager.isPresented)
                assertEquals(0, h.dismissals)
            }
        }

    @Test
    fun `a launch that throws ends the presentation straight away`() =
        Given("an activity start that throws") {
            val h = Harness(launchThrows = true)
            When("the Customer Center is presented") { h.present() }
            Then("it isn't presented, the caller hears, and no timeout is pending") {
                assertFalse(h.manager.isPresented)
                assertEquals(1, h.dismissals)
                assertTrue(h.delayed.isEmpty())
            }
        }
}
