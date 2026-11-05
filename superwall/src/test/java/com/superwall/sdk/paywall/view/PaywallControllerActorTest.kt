package com.superwall.sdk.paywall.view

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.storage.Storage
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallControllerActorTest {
    private fun initialState() = PaywallViewState(paywall = Paywall.stub(), locale = "en_US")

    private fun customerInfo(userId: String) = CustomerInfo.empty().copy(userId = userId, isPlaceholder = false)

    @Test
    fun `updateState applies the update through the actor as a PaywallReducer`() =
        runTest {
            Given("a controller on its actor, with an update interceptor") {
                val controller = PaywallView.PaywallController(initialState(), backgroundScope)
                val seen = mutableListOf<Reducer<PaywallViewState>>()
                controller.actor.onUpdate { reducer, next ->
                    seen += reducer
                    next(reducer)
                }
                val update = PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready)

                When("a public update is applied") {
                    controller.updateState(update)
                }

                Then("the actor saw it wrapped in a PaywallReducer") {
                    assertEquals(1, seen.size)
                    assertSame(update, (seen.single() as PaywallReducer).update)
                }

                Then("state and currentState both show the new loading state") {
                    assertEquals(PaywallLoadingState.Ready, controller.state.loadingState)
                    assertEquals(PaywallLoadingState.Ready, controller.currentState.value.loadingState)
                }
            }
        }

    @Test
    fun `the public constructor still builds a working controller`() {
        Given("a controller made with the public constructor") {
            val controller = PaywallView.PaywallController(initialState())

            When("an update is applied") {
                controller.updateState(PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.LoadingURL))
            }

            Then("its state reflects it") {
                assertEquals(PaywallLoadingState.LoadingURL, controller.state.loadingState)
            }
        }
    }

    @Test
    fun `ObserveCustomerInfo mirrors customer info until it is cancelled`() =
        runTest(UnconfinedTestDispatcher()) {
            Given("a paywall context whose customer info changes over time") {
                val controller = PaywallView.PaywallController(initialState(), backgroundScope)
                val customerInfo = MutableStateFlow(customerInfo("first"))
                val context =
                    PaywallContextImpl(
                        actor = controller.actor,
                        scope = backgroundScope,
                        storage = mockk<Storage>(relaxed = true),
                        tracker = {},
                        customerInfo = { customerInfo },
                    )

                val job =
                    When("the observe action runs") {
                        launch { controller.actor.immediate(context, PaywallActions.ObserveCustomerInfo) }
                    }

                Then("the state takes the current customer info") {
                    assertEquals("first", controller.state.customerInfo.userId)
                }

                When("the customer info changes") {
                    customerInfo.value = customerInfo("second")
                }

                Then("the state follows it") {
                    assertEquals("second", controller.state.customerInfo.userId)
                }

                When("the action is cancelled and the customer info changes again") {
                    job.cancel()
                    customerInfo.value = customerInfo("third")
                }

                Then("the state keeps the last value it saw") {
                    assertTrue(job.isCancelled)
                    assertEquals("second", controller.state.customerInfo.userId)
                }
            }
        }
}
