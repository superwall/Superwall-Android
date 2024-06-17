package com.example.superapp.utils

import android.view.View
import android.webkit.WebView
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dropbox.dropshots.Dropshots
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.paywall.vc.ShimmerView
import com.superwall.superapp.MainActivity
import com.superwall.superapp.test.UITestInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.LinkedList

class ScreenshotTestFlow(
    val testInfo: UITestInfo,
) {
    var steps: LinkedList<Step> = LinkedList()

    fun step(
        name: String,
        action: suspend Dropshots.(UITestInfo) -> Unit,
    ) {
        steps.add(
            Step(name) {
                action(it)
                runBlocking {
                    assertSnapshot("SW_TestCase_${testInfo.number}_$name")
                }
            },
        )
    }

    data class Step(
        val name: String,
        val action: suspend Dropshots.(UITestInfo) -> Unit,
    )
}

@DslMarker
annotation class ScreenshotTestDSL

@DslMarker
annotation class UiTestDSL

@ScreenshotTestDSL
fun Dropshots.screenshotFlow(
    testInfo: UITestInfo,
    flow: ScreenshotTestFlow.() -> Unit,
) {
    val flow = ScreenshotTestFlow(testInfo).apply(flow)
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    val testCase = testInfo
    scenario.moveToState(Lifecycle.State.STARTED)
    scenario.onActivity {
        val ctx = it
        runTest {
            testCase.test(ctx, this@runTest)
        }
    }
    runBlocking {
        flow.steps.forEach {
            it.action(this@screenshotFlow, testCase)
        }
    }
    closeActivity()
    scenario.close()
}

@ScreenshotTestDSL
fun Dropshots.screenshotPaywallTest(
    testInfo: UITestInfo,
    onTest: suspend (UITestInfo) -> Unit,
) {
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    val testCase = testInfo
    scenario.moveToState(Lifecycle.State.STARTED)
    scenario.onActivity {
        val ctx = it
        runBlocking {
            testCase.test(ctx, this)
        }
    }
    runBlocking {
        onTest(testCase)
        // Take screenshot and compare
        assertSnapshot("SW_TestCase_${testCase.number}")
    }
    closeActivity()
    scenario.close()
}

@ScreenshotTestDSL
fun Dropshots.paywallPresentsFor(testInfo: UITestInfo) {
    screenshotPaywallTest(testInfo) {
        it.waitFor { it is SuperwallEvent.PaywallWebviewLoadComplete }
        // Since there is a delay between webview finishing loading and the actual renler
        // We need to wait for the webview to finish loading before taking the snapshot
        awaitUntilShimmerDisappears()
        delay(100)
    }
}

@ScreenshotTestDSL
fun Dropshots.paywallDoesntPresentFor(testInfo: UITestInfo) {
    screenshotPaywallTest(testInfo) {
        it.waitFor { it is SuperwallEvent.PaywallPresentationRequest }
        // We delay a bit to ensure the paywall doesn't render after presentation request
        delay(3000)
    }
}

@UiTestDSL
suspend fun awaitUntilShimmerDisappears() {
    val shimmer =
        Superwall.instance.paywallViewController!!
            .children
            .find { it is ShimmerView }!!
    while (shimmer.visibility == View.VISIBLE) {
        delay(100)
    }
    delay(100)
}

@UiTestDSL
suspend fun awaitUntilWebviewAppears() {
    val shimmer =
        Superwall.instance.paywallViewController!!
            .children
            .find { it is WebView }!!
    while (shimmer.visibility != View.VISIBLE) {
        delay(100)
    }
    delay(100)
}

@UiTestDSL
suspend fun UITestInfo.waitFor(event: (SuperwallEvent) -> Boolean) {
    events()
        .filterNotNull()
        .first(event)
}

// To close the SuperwallPaywallActivity or MainActivity if no paywall was presented
@UiTestDSL
fun closeActivity() {
    getInstrumentation().runOnMainSync {
        ActivityLifecycleMonitorRegistry
            .getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .forEach {
                it.finish()
            }
    }
}
