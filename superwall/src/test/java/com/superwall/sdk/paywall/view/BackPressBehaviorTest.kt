package com.superwall.sdk.paywall.view

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.paywall.Paywall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPressBehaviorTest {
    @Test
    fun app_callback_is_not_consulted_when_reroute_is_disabled() {
        Given("a paywall with reroute_back_button disabled and a consuming app callback") {
            var callbackInvoked = false

            When("the back button is pressed") {
                val consumed =
                    isBackPressConsumedByApp(Paywall.ToggleMode.DISABLED) {
                        callbackInvoked = true
                        true
                    }

                Then("the press is not consumed and the callback is never invoked") {
                    assertFalse(consumed)
                    assertFalse(callbackInvoked)
                }
            }
        }
    }

    @Test
    fun app_callback_is_not_consulted_when_reroute_is_unset() {
        Given("a paywall without a reroute_back_button setting") {
            When("the back button is pressed") {
                val consumed = isBackPressConsumedByApp(null) { true }

                Then("the press is not consumed") {
                    assertFalse(consumed)
                }
            }
        }
    }

    @Test
    fun app_callback_consumes_the_press_when_reroute_is_enabled() {
        Given("a paywall with reroute_back_button enabled and a consuming app callback") {
            When("the back button is pressed") {
                val consumed = isBackPressConsumedByApp(Paywall.ToggleMode.ENABLED) { true }

                Then("the press is consumed by the app") {
                    assertTrue(consumed)
                }
            }
        }
    }

    @Test
    fun press_is_not_consumed_when_reroute_is_enabled_and_app_declines() {
        Given("a paywall with reroute_back_button enabled and a non-consuming app callback") {
            When("the back button is pressed") {
                val consumed = isBackPressConsumedByApp(Paywall.ToggleMode.ENABLED) { false }

                Then("the press is not consumed and falls through to the paywall") {
                    assertFalse(consumed)
                }
            }
        }
    }
}
