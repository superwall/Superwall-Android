package com.example.superapp.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.dropshots.CountValidator
import com.dropbox.dropshots.Dropshots
import com.example.superapp.utils.CustomComparator
import com.example.superapp.utils.awaitUntilShimmerDisappears
import com.example.superapp.utils.awaitUntilWebviewAppears
import com.example.superapp.utils.paywallDoesntPresentFor
import com.example.superapp.utils.paywallPresentsFor
import com.example.superapp.utils.screenshotFlow
import com.example.superapp.utils.screenshotPaywallTest
import com.example.superapp.utils.waitFor
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.superapp.test.UITestHandler
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotTestExecutor {
    @get:Rule
    val dropshots =
        Dropshots(
            resultValidator = CountValidator(1),
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
    fun test_paywall_presents_regardless_of_subscription() =
        with(dropshots) {
            screenshotPaywallTest(UITestHandler.test9Info) {
                it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                awaitUntilShimmerDisappears()
                awaitUntilWebviewAppears()
                delay(500)
                // We scroll a bit to display the button
                Superwall.instance.paywallViewController
                    ?.webView
                    ?.scrollBy(0, 300)
                // We delay a bit to ensure the button is visible
                delay(100)
                // We scroll back to the top
                Superwall.instance.paywallViewController
                    ?.webView
                    ?.scrollTo(0, 0)
                // We delay a bit to ensure scroll has finished
                delay(1000)
            }
        }

    @Test
    fun test_paywall_reappers_with_video() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test4Info) {
                step("first_paywall") {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delay(1000)
                }
                step("first_paywall_end") {
                    delay(4000)
                }
                step("second_paywall") {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilWebviewAppears()
                    delay(1000)
                }
            }
        }

    @Test
    fun test_paywall_reappers_after_dismissing() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test11Info) {
                step {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delay(1000)
                }
                step {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilWebviewAppears()
                    delay(1000)
                }
                step {
                    it.waitFor { it is SuperwallEvent.PaywallClose }
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilWebviewAppears()
                    delay(1000)
                }
            }
        }
}
