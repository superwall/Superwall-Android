package com.superwall.sdk.paywall.presentation

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private var current: PaywallView? = null
    private var cancels = 0

    private fun coordinator(cancel: () -> Unit = { cancels++ }) =
        PresentationCoordinator(
            currentView = { current },
            ioScope = IOScope(dispatcher),
            cancelNotifications = cancel,
        )

    @Test
    fun `presented state follows the current view`() {
        Given("no paywall on screen") {
            val coordinator = coordinator()

            Then("nothing is presented") {
                assertNull(coordinator.paywallView)
                assertFalse(coordinator.isPaywallPresented)
            }

            When("a paywall view appears") {
                val view = mockk<PaywallView>()
                current = view

                Then("it is the presented paywall") {
                    assertEquals(view, coordinator.paywallView)
                    assertTrue(coordinator.isPaywallPresented)
                }
            }
        }
    }

    @Test
    fun `latest paywall info prefers the presented view, then the stored info`() {
        Given("stored info from an earlier presentation") {
            val coordinator = coordinator()
            val stored = mockk<PaywallInfo>()
            coordinator.updatePaywallInfo(stored)

            Then("with nothing presented, the stored info is returned") {
                assertEquals(stored, coordinator.latestPaywallInfo)
            }

            When("a paywall is presented") {
                val presented = mockk<PaywallInfo>()
                current = mockk { every { info } returns presented }

                Then("its info wins") {
                    assertEquals(presented, coordinator.latestPaywallInfo)
                }
            }

            When("the coordinator is reset and nothing is presented") {
                current = null
                coordinator.reset()

                Then("there is no latest info") {
                    assertNull(coordinator.latestPaywallInfo)
                }
            }
        }
    }

    @Test
    fun `stores the last presentation request, ignoring null ones`() {
        Given("a coordinator") {
            val coordinator = coordinator()
            val request = mockk<PresentationRequest>()
            val publisher = MutableSharedFlow<PaywallState>()

            When("a null request is stored") {
                coordinator.storePresentationObjects(null, publisher)

                Then("nothing is remembered") {
                    assertNull(coordinator.presentationItems.last)
                }
            }

            When("a request is stored") {
                coordinator.storePresentationObjects(request, publisher)

                Then("it is the last presentation") {
                    assertEquals(LastPresentationItems(request, publisher), coordinator.presentationItems.last)
                }
            }
        }
    }

    @Test
    fun `toggling the spinner updates the presented view`() =
        runTest(dispatcher) {
            Given("a presented paywall") {
                val view = mockk<PaywallView>(relaxed = true)
                current = view
                val coordinator = coordinator()

                When("the spinner is hidden") {
                    coordinator.togglePaywallSpinner(isHidden = true)
                    advanceUntilIdle()

                    Then("the view gets a ToggleSpinner update") {
                        verify { view.updateState(PaywallViewState.Updates.ToggleSpinner(true)) }
                    }
                }
            }
        }

    @Test
    fun `toggling the spinner with nothing presented does nothing`() =
        runTest(dispatcher) {
            Given("no paywall") {
                val coordinator = coordinator()

                When("the spinner is toggled") {
                    coordinator.togglePaywallSpinner(isHidden = false)
                    advanceUntilIdle()

                    Then("nothing fails") {
                        assertNull(coordinator.paywallView)
                    }
                }
            }
        }

    @Test
    fun `alerts go to the presented view only`() {
        Given("a presented paywall") {
            val view = mockk<PaywallView>(relaxed = true)
            current = view
            val coordinator = coordinator()
            val action = {}

            When("an alert is shown") {
                coordinator.showAlert("Title", "Message", "OK", "Done", action, null)

                Then("the view shows it") {
                    verify { view.showAlert("Title", "Message", "OK", "Done", action, null) }
                }
            }
        }
    }

    @Test
    fun `cancelling notifications calls the scheduler and swallows its errors`() {
        Given("a coordinator") {
            When("notifications are cancelled") {
                val coordinator = coordinator()
                coordinator.cancelAllScheduledNotifications()

                Then("the scheduler is called") {
                    assertEquals(1, cancels)
                }
            }

            When("the scheduler throws") {
                val coordinator = coordinator(cancel = { throw IllegalStateException("no WorkManager") })

                Then("the error does not escape") {
                    coordinator.cancelAllScheduledNotifications()
                }
            }
        }
    }
}
