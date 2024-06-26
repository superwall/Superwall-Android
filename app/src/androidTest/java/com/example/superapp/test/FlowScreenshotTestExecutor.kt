@file:Suppress("ktlint:standard:no-empty-file")

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.dropshots.Dropshots
import com.dropbox.dropshots.ThresholdValidator
import com.example.superapp.utils.CustomComparator
import com.example.superapp.utils.awaitUntilShimmerDisappears
import com.example.superapp.utils.awaitUntilWebviewAppears
import com.example.superapp.utils.delayFor
import com.example.superapp.utils.screenshotFlow
import com.example.superapp.utils.waitFor
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.superapp.test.UITestHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class FlowScreenshotTestExecutor {
    @get:Rule
    val dropshots =
        Dropshots(
            resultValidator = ThresholdValidator(0.01f),
            imageComparator = CustomComparator(),
        )

    @Test
    fun test_paywall_reappers_with_video() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test4Info) {
                step("first_paywall") {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delayFor(100.milliseconds)
                }
                step("second_paywall") {
                    awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
            }
        }

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

    @Test
    fun test_paywall_reappers_after_dismissing() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test11Info) {
                step {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears() || awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
                step {
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
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
    fun test_paywall_calls_reset_after_present() =
        with(dropshots) {
            screenshotFlow(UITestHandler.test34Info) {
                step("") {
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    awaitUntilShimmerDisappears() || awaitUntilWebviewAppears()
                }
                step {
                    it.waitFor { it is SuperwallEvent.Reset }
                    delayFor(100.milliseconds)
                }
            }
        }

    @Test
    fun test_paywall_presents_then_dismisses_without_reappearing() =
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
                    delayFor(1.seconds)
                }

                step {
                    it.waitFor { it is SuperwallEvent.PaywallClose }
                }
            }
        }
}
