package com.example.superapp.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.dropshots.Dropshots
import com.dropbox.dropshots.ThresholdValidator
import com.example.superapp.utils.CustomComparator
import com.example.superapp.utils.delayFor
import com.example.superapp.utils.screenshotFlow
import com.example.superapp.utils.waitFor
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.superapp.test.UITestHandler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class PresentationRuleTests {
    @get:Rule
    val dropshots =
        Dropshots(
            resultValidator = ThresholdValidator(0.01f),
            imageComparator = CustomComparator(),
        )

    @Test
    fun test_paywall_doesnt_present_result_experiment() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test28Info) {
                step("") {
                    delayFor(1.seconds)
                }
            }
        }

    @Test
    fun test_paywall_doesnt_present_result_no_rule_match() =
        with(dropshots) {
            Superwall.instance.reset()
            screenshotFlow(UITestHandler.test29Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.UserAttributes }
                    delayFor(1.seconds)
                }
            }
        }

    @Test
    fun test_paywall_doesnt_present_result_event_not_found() =
        with(dropshots) {
            Superwall.instance.reset()
            screenshotFlow(UITestHandler.test30Info) {
                step("") {
                    delayFor(1.seconds)
                }
            }
        }

    @Test
    fun test_paywall_doesnt_present_result_holdout() =
        with(dropshots) {
            Superwall.instance.reset()
            screenshotFlow(UITestHandler.test31Info) {
                step("") {
                    delayFor(1.seconds)
                }
            }
        }

    @Test
    fun test_paywall_doesnt_present_result_subscription_change() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test32Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.EntitlementStatusDidChange }
                    delayFor(1.seconds)
                }
            }
        }
}
