package com.example.superapp.utils

import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.dropbox.dropshots.Dropshots
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.paywall.view.ShimmerView
import com.superwall.superapp.MainActivity
import com.superwall.superapp.test.EventTimeline
import com.superwall.superapp.test.TimelineStore
import com.superwall.superapp.test.UITestInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.LinkedList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScreenshotTestFlow(
    val testInfo: UITestInfo,
) {
    var steps: LinkedList<Step> = LinkedList()

    // Baselines are kept per device profile, as full-screen captures only compare between
    // devices with the same screen and system bars. See ScreenshotProfile.
    private val device = ScreenshotDevice.profile.prefix

    /**
     * Runs [action], waits for the screen to stop changing and compares it with the baseline.
     *
     * [timeline], when given, is checked against the events recorded so far before the
     * screenshot is compared. Both are always checked, so a failure reports what the SDK did
     * as well as what the screen looked like.
     *
     * [settleTimeout] is how long to wait for the screen to stop changing. Shorten it for a
     * screen that never does, such as a video, when the test moves on by itself: the default
     * would capture whatever is showing five seconds later.
     */
    @ScreenshotTestDSL
    fun step(
        name: String? = null,
        timeline: (EventTimeline.() -> Unit)? = null,
        settleTimeout: Duration = 5.seconds,
        action: suspend CoroutineScope.(UITestInfo) -> Unit,
    ) {
        val stepName = name ?: "step_${steps.size + 1}"
        steps.add(
            Step(stepName) { it, et ->

                val step = "${device}_TestCase_${testInfo.number}${
                    if (stepName.isEmpty()) "" else "_$stepName"
                }"
                Log.e(
                    "Testing Flow",
                    "Executing step $step",
                )

                action(et, it)
                val frame = awaitScreenSettled(timeout = settleTimeout)
                val timelineFailure = runCatching { timeline?.invoke(testInfo.timeline) }.exceptionOrNull()
                Log.e("Testing Flow", "Taking screenshot of $step")
                // A screenshot failure says the screen was wrong. The events say how it got there.
                val screenshotFailure =
                    runCatching { assertSnapshot(frame, step) }.exceptionOrNull()?.let {
                        AssertionError("${it.message} Recorded: ${testInfo.timeline.eventNames()}", it)
                    }
                Log.e("Testing Flow", "Taken screenshot of $step")
                when {
                    timelineFailure != null && screenshotFailure != null ->
                        throw AssertionError(
                            "Events and screenshot both failed.\nEvents: ${timelineFailure.message}\n" +
                                "Screenshot: ${screenshotFailure.message}",
                            timelineFailure,
                        ).apply { addSuppressed(screenshotFailure) }
                    timelineFailure != null -> throw timelineFailure
                    screenshotFailure != null -> throw screenshotFailure
                }
            },
        )
    }

    data class Step(
        val name: String,
        val action: suspend Dropshots.(UITestInfo, CoroutineScope) -> Unit,
    )
}

@DslMarker
annotation class ScreenshotTestDSL

@DslMarker
annotation class UiTestDSL

@ScreenshotTestDSL
fun Dropshots.screenshotFlow(
    testInfo: UITestInfo,
    config: FlowTestConfiguration = FlowTestConfiguration(),
    flow: ScreenshotTestFlow.() -> Unit,
) {
    ScreenshotDevice.requireComparable()
    val flow = ScreenshotTestFlow(testInfo).apply(flow)
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    val testCase = testInfo

    println("-----------")
    println("Executing test case: ${testCase.number}")
    println("Description:\n ${testCase.description}")
    println("-----------")
    val testReady = MutableStateFlow(false)
    // Moving the resumed activity down to STARTED puts ActivityScenario's empty floating activity
    // on top of it, which dims the screen. The baselines of the app without a paywall were taken
    // that way. It can't be done when a paywall shown at app launch already covers the activity.
    runCatching { scenario.moveToState(Lifecycle.State.STARTED) }
    val scope = CoroutineScope(Dispatchers.IO)
    scenario.onActivity {
        val ctx = it

        runBlocking {
            scope.launch {
                testReady.first { it }
                testCase.test(ctx)
            }
        }
    }

    runTest(timeout = config.timeout) {
        if (config.waitForConfig) {
            Superwall.instance.configurationStateListener.first { it is ConfigurationStatus.Configured }
        }
        try {
            flow.steps.forEach {
                if (!testReady.value) {
                    testReady.update { true }
                }
                it.action(this@screenshotFlow, testCase, this)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            scope.cancel()
            throw e
        } finally {
            scope.cancel()
            writeTimelineToFile(testCase)
            TimelineStore.remove("Test #${testCase.number}")
        }
    }
    closeActivity()
    scenario.close()
}

/**
 * The paywall presents: the events say it loaded and opened, and the screen matches the baseline.
 */
@ScreenshotTestDSL
fun Dropshots.paywallPresentsFor(
    testInfo: UITestInfo,
    config: FlowTestConfiguration = FlowTestConfiguration(),
    /**
     * The paywall is shown by the app launching, not by the test case. Any of its events, up to
     * and including the page load, can then fire before the test starts recording, so the
     * events aren't asserted and the paywall's view is waited for instead.
     */
    presentedAtLaunch: Boolean = false,
) {
    screenshotFlow(testInfo, config) {
        step("", timeline = if (presentedAtLaunch) null else ({ assertPaywallPresented() })) {
            if (presentedAtLaunch) awaitPaywallView() else it.timeline.awaitPaywallLoaded()
            awaitUntilShimmerDisappears()
            awaitUntilWebviewAppears()
            // The load event comes before the page's fonts and images, and the open event can
            // come after it. The step then waits for the screen to stop changing.
            awaitPaywallContentReady()
            if (!presentedAtLaunch) it.timeline.awaitEvent { it is SuperwallEvent.PaywallOpen }
        }
    }
}

/**
 * The paywall doesn't present: the events say the request was turned down and nothing opened,
 * and the screen still matches the baseline of the app without a paywall.
 */
@ScreenshotTestDSL
fun Dropshots.paywallDoesntPresentFor(
    testInfo: UITestInfo,
    config: FlowTestConfiguration = FlowTestConfiguration(),
) {
    screenshotFlow(testInfo, config) {
        step("", timeline = { assertPaywallNotPresented() }) {
            it.waitFor { it is SuperwallEvent.PaywallPresentationRequest }
            // We delay a bit to ensure the paywall doesn't render after presentation request
            delayFor(1.seconds)
        }
    }
}

@ScreenshotTestDSL
fun Dropshots.paywallDoesntPresentForNoConfig(
    testInfo: UITestInfo,
    config: FlowTestConfiguration = FlowTestConfiguration(false),
) {
    screenshotFlow(testInfo, config) {
        step("", timeline = { assertPaywallNotPresented() }) {
            it.waitFor { it is SuperwallEvent.PaywallPresentationRequest }
            // We delay a bit to ensure the paywall doesn't render after presentation request
            delayFor(1.seconds)
        }
    }
}

/** Waits until a paywall is on screen, going by its view where the events can't be relied on. */
@UiTestDSL
suspend fun awaitPaywallView(timeout: Duration = 60.seconds) {
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeout) {
            while (getWebviewFromPaywall()?.isShown != true) delay(100.milliseconds)
        }
    } ?: throw AssertionError("No paywall was on screen within $timeout.")
}

/**
 * Waits for the paywall's loading placeholder to go. With no paywall up there is no placeholder,
 * so this returns at once: a step that expects a paywall must wait for it first
 * ([awaitPaywallView] or a load event), or it can capture the placeholder a moment later.
 */
@UiTestDSL
suspend fun CoroutineScope.awaitUntilShimmerDisappears(): Boolean {
    while (getShimmerFromPaywall()?.visibility == View.VISIBLE) {
        delayFor(100.milliseconds)
    }
    return true
}

private fun getShimmerFromPaywall() =
    Superwall.instance.paywallView
        ?.children
        ?.find { it is ShimmerView }

private fun getWebviewFromPaywall() =
    Superwall.instance.paywallView
        ?.children
        ?.find { it is WebView }

@UiTestDSL
suspend fun awaitUntilWebviewAppears(): Boolean {
    val device = UiDevice.getInstance(getInstrumentation())
    device.wait(Until.findObject(By.clazz(WebView::class.java.name)), 1000)
    device.waitForIdle()
    delay(300.milliseconds)
    return true
}

@UiTestDSL
suspend fun enableWebviewDebugging() {
    UiDevice
        .getInstance(getInstrumentation())
        .findObject(By.clazz(WebView::class.java))
        .apply {
        }
}

@UiTestDSL
suspend fun clickButtonWith(text: String) {
    val selector = UiSelector()
    val device = UiDevice.getInstance(getInstrumentation())
    device
        .findObject(
            UiSelector().textContains(text),
        ).click()
}

@UiTestDSL
suspend fun setInput(text: String) {
    val device = UiDevice.getInstance(getInstrumentation())
    with(
        device.findObject(
            UiSelector().focusable(true),
        ),
    ) {
        this.setText(text)
    }
}

@UiTestDSL
suspend fun goBack() {
    val device = UiDevice.getInstance(getInstrumentation())
    device.pressBack()
}

/**
 * Closes the paywall and waits for the close event, so what follows doesn't race the dismissal.
 *
 * This doesn't press back. The SDK hands a back press to the paywall's page to answer, and a
 * paywall built on a runtime from before `back_button_input` ignores it and stays up.
 */
@UiTestDSL
suspend fun UITestInfo.dismissPaywallAndAwaitClose(timeout: Duration = 10.seconds) {
    Superwall.instance.dismiss()
    timeline.awaitEvent(timeout) { it is SuperwallEvent.PaywallClose }
        ?: throw AssertionError(
            "The paywall didn't close within $timeout of being dismissed. Recorded: ${timeline.eventNames()}",
        )
}

@UiTestDSL
suspend fun awaitUntilDialogAppears(): Boolean {
    val selector = UiSelector()
    val device = UiDevice.getInstance(getInstrumentation())
    device.wait(Until.findObject(By.clazz(AlertDialog::class.java.name)), 10000)
    return true
}

@UiTestDSL
suspend fun awaitUntilWebviewDisappears() {
    while (getWebviewFromPaywall()?.visibility == View.VISIBLE) {
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

suspend fun CoroutineScope.delayFor(duration: Duration) =
    async(Dispatchers.IO) {
        delay(duration)
    }.await()

// --- EventTimeline extensions ---

/** Access the timeline for this test info. */
val UITestInfo.eventTimeline: EventTimeline get() = timeline

/** Assert that the duration from timeline start to event T is under the given limit. */
inline fun <reified T : SuperwallEvent> EventTimeline.assertDurationToUnder(limit: Duration) {
    val actual = durationTo<T>()
        ?: error("Event ${T::class.simpleName} was never recorded")
    assert(actual <= limit) {
        "Expected ${T::class.simpleName} within $limit but took $actual"
    }
}

/** Assert that the duration between events A and B is under the given limit. */
inline fun <reified A : SuperwallEvent, reified B : SuperwallEvent> EventTimeline.assertDurationBetweenUnder(limit: Duration) {
    val actual = durationBetween<A, B>()
        ?: error("One or both events (${A::class.simpleName}, ${B::class.simpleName}) were never recorded")
    assert(actual <= limit) {
        "Expected ${A::class.simpleName} -> ${B::class.simpleName} within $limit but took $actual"
    }
}

/** Print a human-readable summary of all captured events to logcat. */
fun EventTimeline.printSummary(tag: String = "EventTimeline") {
    allEvents().forEachIndexed { i, event ->
        Log.i(tag, "#$i [${event.elapsed}] ${event.eventName} (${event.eventType}) params=${event.params}")
    }
    Log.i(tag, "Total duration: ${totalDuration()}")
}
