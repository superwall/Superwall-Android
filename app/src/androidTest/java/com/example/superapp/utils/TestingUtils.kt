package com.example.superapp.utils

import android.view.View
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

@DslMarker
annotation class ScreenshotTestDSL

@DslMarker
annotation class UiTestDSL

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
            testCase.test(ctx)
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
