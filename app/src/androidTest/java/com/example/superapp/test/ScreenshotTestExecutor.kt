package com.example.superapp.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.dropbox.dropshots.Dropshots
import com.dropbox.dropshots.ThresholdValidator
import com.example.superapp.utils.CustomComparator
import com.example.superapp.utils.awaitUntilShimmerDisappears
import com.example.superapp.utils.awaitUntilWebviewAppears
import com.example.superapp.utils.paywallDoesntPresentFor
import com.example.superapp.utils.paywallPresentsFor
import com.example.superapp.utils.screenshotFlow
import com.example.superapp.utils.waitFor
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.superapp.test.UITestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class ScreenshotTestExecutor {
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

    @LargeTest
    @Test
    fun test_paywall_presents_regardless_of_subscription() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test9Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delayFor(500.milliseconds)
                    // We scroll a bit to display the button
                    Superwall.instance.paywallViewController
                        ?.webView
                        ?.scrollBy(0, 300)
                    // We delay a bit to ensure the button is visible
                    delayFor(100.milliseconds)
                    // We scroll back to the top
                    Superwall.instance.paywallViewController
                        ?.webView
                        ?.scrollTo(0, 0)
                    // We delay a bit to ensure scroll has finished
                    delayFor(1.seconds)
                }
            }
        }

    @LargeTest
    @Test
    fun test_paywall_reappers_with_video() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test4Info) {
                step("first_paywall") {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
                step("second_paywall") {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
            }
        }

    @LargeTest
    @Test
    fun test_paywall_reappers_after_dismissing() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test11Info) {
                step {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears() || awaitUntilWebviewAppears()
                }
                step {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    // delayFor(1.seconds)
                    awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
                step {
                    it.waitFor { it is SuperwallEvent.PaywallClose }
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
            }
        }

    @Test
    fun test_paywall_doesnt_display_with_trigger_off() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test12Info) {
                step {
                    it.waitFor {
                        it is SuperwallEvent.PaywallPresentationRequest &&
                            it.reason is PaywallPresentationRequestStatusReason.EventNotFound
                    }

                    delayFor(1.seconds)
                }
            }
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
            screenshotFlow(UITestHandler.test18Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilWebviewAppears()
                }
            }
        }

    @Test
    fun test_paywall_presents_with_unsubscribe() =
        with(dropshots) {
            paywallPresentsFor(UITestHandler.test23Info)
        }

    @Test
    fun test_paywall_doesnt_present_pregated() =
        with(dropshots) {
            paywallDoesntPresentFor(UITestHandler.test23Info)
        }

    @Test
    fun test_paywall_presents_then_dismisses_without_reappearing() =
        with(dropshots) {
            with(dropshots) {
                screenshotFlow(UITestHandler.test14Info) {
                    step {
                        it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                        awaitUntilShimmerDisappears()
                        awaitUntilWebviewAppears()
                        delayFor(500.milliseconds)
                        launch(Dispatchers.Main) {
                            // We scroll a bit to display the button
                            Superwall.instance.paywallViewController
                                ?.webView
                                ?.scrollBy(0, 300)
                        }
                        // We delay a bit to ensure the button is visible
                        delayFor(100.milliseconds)
                        // We scroll back to the top
                        launch(Dispatchers.Main) {
                            Superwall.instance.paywallViewController
                                ?.webView
                                ?.scrollTo(0, 0)
                        }
                        // We delay a bit to ensure scroll has finished
                        delayFor(1000.milliseconds)
                    }

                    step {
                        it.waitFor { it is SuperwallEvent.PaywallClose }
                    }
                }
            }
        }
}

suspend fun CoroutineScope.delayFor(duration: Duration) =
    async(Dispatchers.IO) {
        delay(duration)
    }.await()
