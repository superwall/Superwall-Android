package com.example.superapp.test

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dropbox.dropshots.Dropshots
import com.dropbox.dropshots.ThresholdValidator
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.superapp.MainActivity
import com.superwall.superapp.test.UITestHandler
import com.superwall.superapp.test.UITestInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotTestExecutor {
    @get:Rule
    val dropshots =
        Dropshots(
            resultValidator = ThresholdValidator(0.01f),
        )

    @Test
    fun test_paywall_displays_with_attribute_first() {
        paywallPresentsFor(UITestHandler.test0Info)
    }

    @Test
    fun test_paywall_displays_with_attribute_second() {
        paywallPresentsFor(UITestHandler.test1Info)
    }

    @Test
    fun test_paywall_displays_without_attribute() {
        paywallPresentsFor(UITestHandler.test2Info)
    }

    @Test
    fun test_paywall_displays_with_two_products_alt() {
        paywallPresentsFor(UITestHandler.test6Info)
    }

    @Test
    fun test_paywall_doesnt_display() {
        paywallDoesntPresentFor(UITestHandler.test8Info)
    }

    @Test
    fun test_paywall_presents_regardless_of_subscription() {
        screenshotPaywallTest(UITestHandler.test9Info) {
            it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
            delay(1000)

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

    private fun screenshotPaywallTest(
        testInfo: UITestInfo,
        onTest: suspend (UITestInfo) -> Unit,
    ) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val testCase = testInfo
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity {
            val ctx = it
            runBlocking {
                testCase.test(ctx)
            }
        }
        runBlocking {
            onTest(testCase)
            // Take screenshot and compare
            dropshots.assertSnapshot("SW_TestCase_${testCase.number}")
        }
        closeActivity()
        scenario.close()
    }

    private fun paywallPresentsFor(testInfo: UITestInfo) {
        screenshotPaywallTest(testInfo) {
            it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
            // Since there is a delay between webview finishing loading and the actual renler
            // We need to wait for the webview to finish loading before taking the snapshot
            delay(3000)
        }
    }

    private fun paywallDoesntPresentFor(testInfo: UITestInfo) {
        screenshotPaywallTest(testInfo) {
            it.waitFor { it is SuperwallEvent.PaywallPresentationRequest }
            // We delay a bit to ensure the paywall doesn't render after presentation request
            delay(3000)
        }
    }
}

private suspend fun UITestInfo.waitFor(event: (SuperwallEvent) -> Boolean) {
    events()
        .filterNotNull()
        .first(event)
}

// To close the SuperwallPaywallActivity or MainActivity if no paywall was presented
private fun closeActivity() {
    getInstrumentation().runOnMainSync {
        ActivityLifecycleMonitorRegistry
            .getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .forEach {
                it.finish()
            }
    }
}
