package com.example.superapp.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.dropshots.Dropshots
import com.dropbox.dropshots.ThresholdValidator
import com.example.superapp.utils.CustomComparator
import com.example.superapp.utils.awaitUntilDialogAppears
import com.example.superapp.utils.awaitUntilShimmerDisappears
import com.example.superapp.utils.awaitUntilWebviewAppears
import com.example.superapp.utils.delayFor
import com.example.superapp.utils.paywallDoesntPresentFor
import com.example.superapp.utils.paywallPresentsFor
import com.example.superapp.utils.screenshotFlow
import com.example.superapp.utils.waitFor
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.superapp.test.UITestHandler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class SimpleScreenshotTestExecutor {
    @get:Rule
    val dropshots =
        Dropshots(
            resultValidator = ThresholdValidator(0.01f),
            imageComparator = CustomComparator(),
        )

    @Test
    fun test_paywall_displays_with_attribute_first() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test0Info)
        }

    @Test
    fun test_paywall_displays_with_attribute_second() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test1Info)
        }

    @Test
    fun test_paywall_displays_without_attribute() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test2Info)
        }

    @Test
    fun test_paywall_displays_with_two_products_alt() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test6Info)
        }

    @Test
    fun test_paywall_doesnt_display() =
        with(dropshots) {
            paywallDoesntPresentFor(UITestHandler.test8Info)
        }

    @Test
    fun test_paywall_doesnt_display_with_trigger_off() =
        with(dropshots) {
            paywallDoesntPresentFor(UITestHandler.test12Info)
        }

    @Test
    fun test_paywall_doesnt_display_with_trigger_not_in_dashboard() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test13Info) {
                step {
                    delayFor(1.seconds)
                }
            }
        }

    @Test
    fun test_paywall_presents_with_handler_invoked() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test16Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears() || awaitUntilWebviewAppears()
                    delayFor(2.seconds)
                }
            }
        }

    @Test
    fun test_paywall_presents_with_browser() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test18Info)
        }

    @Test
    fun test_paywall_presents_with_unsubscribe() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test23Info)
        }

    @Test
    fun test_paywall_doesnt_present_pregated() =
        with(dropshots) {
            paywallDoesntPresentFor(UITestHandler.test24Info)
        }

    @Test
    fun test_paywall_doesnt_present_without_feature_gate() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test25Info) {
                step {
                    it.waitFor { it is SuperwallEvent.PaywallPresentationRequest }
                }
            }
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)
        }

    @Test
    fun test_paywall_doesnt_present_without_showing_alert_after_dismiss() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test26Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.PaywallPresentationRequest }
                    awaitUntilDialogAppears()
                }
            }
        }

    @Test
    fun test_paywall_doesnt_present_calls_feature_block() =
        with(dropshots) {
            //   Superwall.instance.reset()
            screenshotFlow(UITestHandler.test27Info) {
                step("") {
                    awaitUntilDialogAppears()
                }
            }
        }

    @Test
    fun test_paywall_presents_double_identify_with_same_id() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test33Info)
        }
}
