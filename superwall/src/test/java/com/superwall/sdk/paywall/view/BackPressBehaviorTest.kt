package com.superwall.sdk.paywall.view

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.paywall.Paywall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackPressBehaviorTest {
    @Test
    fun dismisses_when_reroute_is_disabled() {
        Given("a paywall with reroute_back_button disabled and a consuming app callback") {
            var callbackInvoked = false

            When("the back button is pressed") {
                val behavior =
                    backPressBehavior(Paywall.ToggleMode.DISABLED) {
                        callbackInvoked = true
                        true
                    }

                Then("the paywall dismisses without consulting the app callback") {
                    assertEquals(BackPressBehavior.DISMISS, behavior)
                    assertFalse(callbackInvoked)
                }
            }
        }
    }

    @Test
    fun dismisses_when_reroute_is_unset() {
        Given("a paywall without a reroute_back_button setting") {
            When("the back button is pressed") {
                val behavior = backPressBehavior(null) { true }

                Then("the paywall dismisses") {
                    assertEquals(BackPressBehavior.DISMISS, behavior)
                }
            }
        }
    }

    @Test
    fun app_callback_gets_first_refusal_when_reroute_is_enabled() {
        Given("a paywall with reroute_back_button enabled and a consuming app callback") {
            When("the back button is pressed") {
                val behavior = backPressBehavior(Paywall.ToggleMode.ENABLED) { true }

                Then("the press is consumed by the app") {
                    assertEquals(BackPressBehavior.CONSUMED_BY_APP, behavior)
                }
            }
        }
    }

    @Test
    fun forwards_to_paywall_when_reroute_is_enabled_and_app_declines() {
        Given("a paywall with reroute_back_button enabled and a non-consuming app callback") {
            When("the back button is pressed") {
                val behavior = backPressBehavior(Paywall.ToggleMode.ENABLED) { false }

                Then("the press is forwarded into the paywall") {
                    assertEquals(BackPressBehavior.FORWARD_TO_PAYWALL, behavior)
                }
            }
        }
    }
}
