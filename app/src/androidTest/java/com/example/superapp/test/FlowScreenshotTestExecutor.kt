@file:Suppress("ktlint:standard:no-empty-file")

package com.example.superapp.test

/*

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
                    Log.e("Event", "first_paywall waiting for load complete")
                    it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
                    Log.e("Event", "first_paywall load complete")
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    Log.e("Event", "first_paywall webview load")
                    delayFor(100.milliseconds)
                }
                step("second_paywall") {
                    Log.e("Event", "second_paywall paywall open")
                    awaitUntilWebviewAppears()
                    Log.e("Event", "second_paywall delay")
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
                    // delayFor(1.seconds)
                    awaitUntilWebviewAppears()
                    delayFor(1.seconds)
                }
                step {
                    it.waitFor { it is SuperwallEvent.PaywallClose }
                    it.waitFor { it is SuperwallEvent.PaywallOpen }
                    // delayFor(1.seconds)
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
                    delayFor(100.milliseconds)
                }

                step {
                    it.waitFor { it is SuperwallEvent.PaywallClose }
                }
            }
        }

}
 */
