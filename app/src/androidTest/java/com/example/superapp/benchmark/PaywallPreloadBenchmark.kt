package com.example.superapp.benchmark

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.DefaultClassifierDataFactory
import com.superwall.sdk.analytics.DeviceClassifier
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.models.ConfigurationStatus
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.store.testmode.TestModeBehavior
import com.superwall.superapp.BuildConfig
import com.superwall.superapp.Keys
import com.superwall.superapp.benchmark.BenchmarkForegroundActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Measures how long paywall preloading takes on the dev app's embedded API key
 * ([Keys.CONSTANT_API_KEY]).
 *
 * A paywall only counts as loaded once its [PaywallView] reaches
 * [PaywallLoadingState.Ready] — i.e. the webview has loaded, paywall.js has sent
 * `onReady` and the product/user templates have been accepted. Webview load
 * completion alone is NOT enough.
 *
 * The benchmark runs `benchmarkIterations` iterations (instrumentation arg,
 * default 3). Each iteration configures the SDK from scratch, waits for config,
 * triggers [Superwall.preloadAllPaywalls] and polls the SDK's view store until
 * every paywall announced by [SuperwallEvent.PaywallPreloadStart] is Ready.
 * Iteration 1 is cold (orchestrator clears package data before the test); later
 * iterations run after [Superwall.teardown] and are reported separately as warm.
 *
 * Results are written as JSON to the shared Downloads collection so CI can pull
 * them after the run (the app is uninstalled by connectedAndroidTest, so
 * app-scoped storage would be wiped):
 *
 *   adb pull /sdcard/Download/superwall-benchmark app/build/outputs/benchmark
 *
 * Instrumentation args:
 *  - benchmarkIterations: number of preload iterations (default 3)
 *  - benchmarkDeviceTier: LOW | MID | HIGH — recorded in the report (default UNKNOWN)
 *  - benchmarkTimeoutSec: per-iteration timeout waiting for all paywalls Ready (default 300)
 */
@OptIn(ExperimentalCoroutinesApi::class) // MutableSharedFlow.resetReplayCache
@RunWith(AndroidJUnit4::class)
class PaywallPreloadBenchmark {
    private companion object {
        const val TAG = "SWPreloadBenchmark"
        const val RESULTS_DIR = "superwall-benchmark"
        const val POLL_INTERVAL_MS = 50L
        const val SCHEMA_VERSION = 1
    }

    private val args = InstrumentationRegistry.getArguments()
    private val iterations = args.getString("benchmarkIterations")?.toIntOrNull() ?: 3
    private val deviceTier = args.getString("benchmarkDeviceTier")?.uppercase() ?: "UNKNOWN"
    private val timeoutSec = args.getString("benchmarkTimeoutSec")?.toLongOrNull() ?: 300L

    // CI invokes the benchmark several times per emulator (clearing app data
    // before each run, so iteration 0 is always cold) and averages across runs;
    // the index keeps the result files apart.
    private val runIndex = args.getString("benchmarkRunIndex")?.toIntOrNull() ?: 1

    // Separate (shorter) bound for SDK configuration. This must NEVER be
    // unbounded: if the config fetch fails or stalls on the emulator, the
    // status stays Pending/Failed and an unbounded wait hangs the whole CI job.
    private val configureTimeoutSec = args.getString("benchmarkConfigureTimeoutSec")?.toLongOrNull() ?: 120L

    // Comma-separated placement names to preload. A pinned placement set keeps
    // the measured workload stable (the dev key has ~47 paywalls in total —
    // preloading all of them buries low-tier emulators in concurrent WebViews
    // and couples the metric to unrelated dashboard campaign edits). Blank
    // means preloadAllPaywalls().
    private val placements =
        args
            .getString("benchmarkPlacements")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    // Events paired with SystemClock.elapsedRealtime() at emission so durations
    // are measured at fire time, not collection time. Replay so events emitted
    // between preloadAllPaywalls() and the collector attaching (they fire from
    // the SDK's IO scope) aren't missed.
    private val events =
        MutableSharedFlow<Pair<SuperwallEvent, Long>>(replay = 64, extraBufferCapacity = 512)

    private val delegate =
        object : SuperwallDelegate {
            override fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
                events.tryEmit(eventInfo.event to SystemClock.elapsedRealtime())
            }
        }

    // CI emulators have no signed-in Google account, so Play Billing reports
    // BILLING_UNAVAILABLE and never connects — which stalls configuration and
    // leaves entitlement status "unknown". An external purchase controller +
    // explicit subscription status takes the whole billing subsystem out of
    // the path, like any app that doesn't rely on Play Billing.
    private val purchaseController =
        object : PurchaseController {
            override suspend fun purchase(
                activity: Activity,
                productDetails: ProductDetails,
                basePlanId: String?,
                offerId: String?,
            ): PurchaseResult = PurchaseResult.Cancelled()

            override suspend fun restorePurchases(): RestorationResult =
                RestorationResult.Failed(Exception("Not supported in benchmark"))
        }

    private data class IterationResult(
        val index: Int,
        val cold: Boolean,
        val configureMs: Long,
        val paywallCount: Int,
        val allReadyMs: Long,
        val preloadCompleteEventMs: Long?,
        val perPaywallReadyMs: Map<String, Long>,
    )

    @Test
    fun benchmarkPaywallPreload() {
        val app =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.applicationContext as Application

        Log.i(TAG, "Starting preload benchmark: tier=$deviceTier iterations=$iterations timeout=${timeoutSec}s")

        // MainApplication installs a VmPolicy with detectActivityLeaks() +
        // penaltyDeath(), which kills the process without an AndroidRuntime
        // trace mid-benchmark (webview teardown/reconfigure cycles trip it).
        // The dev app's watchdog isn't what we're measuring — disable it.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().build())

        // The SDK's config fetch gates on awaitUntilAppInForeground(), so the app
        // must have a resumed activity or configuration suspends indefinitely.
        // A bare activity (not MainActivity) avoids appcompat's lazy EmojiCompat
        // setup and its GMS FontsProvider connection — see
        // BenchmarkForegroundActivity for the full story.
        val scenario = ActivityScenario.launch(BenchmarkForegroundActivity::class.java)

        val results = mutableListOf<IterationResult>()
        try {
            repeat(iterations) { index ->
                results += runBlocking { runIteration(app, index) }
                // Teardown only BETWEEN iterations (needed to reconfigure). It is
                // deliberately skipped after the last one: stray SDK coroutines
                // touching Superwall.instance post-teardown throw an uncaught
                // IllegalStateException that kills the process before results are
                // written. CI uses iterations=1, so teardown never runs there —
                // each am-instrument run is already cold via pm clear.
                if (index < iterations - 1) {
                    Superwall.teardown()
                    // Give webview destruction and teardown a moment to settle so
                    // the next iteration doesn't race the previous instance.
                    runBlocking { delay(1.seconds) }
                }
            }
        } finally {
            scenario.close()
            // Always persist whatever finished so CI can surface partial data on failure.
            if (results.isNotEmpty()) {
                writeResults(app, results)
            }
        }
    }

    private suspend fun runIteration(
        app: Application,
        index: Int,
    ): IterationResult {
        Log.i(TAG, "Iteration $index: configuring SDK")
        events.resetReplayCache()
        val configureMark = TimeSource.Monotonic.markNow()

        Superwall.configure(
            app,
            Keys.CONSTANT_API_KEY,
            purchaseController = purchaseController,
            options =
                SuperwallOptions().apply {
                    // Debug so CI logcat shows exactly where configuration/preload
                    // stalls; logs print outside the measured Ready window's hot
                    // path and identically across runs, so comparisons stay fair.
                    logging.level = LogLevel.debug
                    // Baked in at build time via -PbenchmarkTestMode=true: CI
                    // emulators have no Play account, and test mode makes
                    // BILLING_UNAVAILABLE non-fatal for paywalls with products.
                    if (BuildConfig.BENCHMARK_TEST_MODE) {
                        testModeBehavior = TestModeBehavior.ALWAYS
                    }
                    paywalls =
                        PaywallOptions().apply {
                            // Preloading is triggered manually below so the measurement
                            // window starts at a known point instead of somewhere inside
                            // configure().
                            shouldPreload = false
                        }
                },
        )
        Superwall.instance.delegate = delegate
        // Active with entitlements (not Inactive): implicit placements
        // (app_install/app_launch/session_start/on_start) would otherwise
        // PRESENT paywalls over the foreground activity mid-benchmark — heavy
        // main-thread WebView work on a slow emulator that can ANR-kill the
        // process. CHECK_USER_SUBSCRIPTION presentations skip for subscribed
        // users, while the preload paths don't gate on subscription status.
        Superwall.instance.setSubscriptionStatus("default", "test")
        val status =
            withTimeoutOrNull(configureTimeoutSec.seconds) {
                Superwall.instance.configurationStateListener.first { it !is ConfigurationStatus.Pending }
            } ?: error(
                "Timed out after ${configureTimeoutSec}s waiting for SDK configuration " +
                    "(status still Pending) — check emulator network / Superwall API reachability",
            )
        check(status is ConfigurationStatus.Configured) {
            "SDK configuration failed (status=$status) — cannot benchmark preloading"
        }
        val configureMs = configureMark.elapsedNow().inWholeMilliseconds
        Log.i(TAG, "Iteration $index: configured in ${configureMs}ms")

        return withTimeout(timeoutSec.seconds) {
            val preloadMark = TimeSource.Monotonic.markNow()
            val preloadStartRealtime = SystemClock.elapsedRealtime()
            if (placements.isEmpty()) {
                Superwall.instance.preloadAllPaywalls()
            } else {
                Superwall.instance.preloadPaywalls(placements)
            }

            val paywallCount =
                (
                    events
                        .first { it.first is SuperwallEvent.PaywallPreloadStart }
                        .first as SuperwallEvent.PaywallPreloadStart
                ).paywallCount
            check(paywallCount > 0) {
                "Preload started with 0 paywalls — the benchmark API key must have preloadable paywalls"
            }
            Log.i(TAG, "Iteration $index: preloading $paywallCount paywalls")

            // Poll the SDK's view store until every announced paywall is Ready.
            val readyAt = mutableMapOf<String, Long>()
            while (readyAt.size < paywallCount) {
                for (view in paywallViews()) {
                    val id = view.state.paywall.identifier
                    if (id !in readyAt && view.state.loadingState is PaywallLoadingState.Ready) {
                        readyAt[id] = preloadMark.elapsedNow().inWholeMilliseconds
                        Log.i(TAG, "Iteration $index: $id ready after ${readyAt[id]}ms (${readyAt.size}/$paywallCount)")
                    }
                }
                if (readyAt.size < paywallCount) delay(POLL_INTERVAL_MS.milliseconds)
            }
            val allReadyMs = readyAt.values.max()

            // The preload-complete event fires when all paywall requests resolved
            // (before Ready), so it should already be buffered; don't block on it.
            val preloadCompleteEventMs =
                withTimeoutOrNull(10.seconds) {
                    events
                        .first { it.first is SuperwallEvent.PaywallPreloadComplete }
                        .second - preloadStartRealtime
                }

            Log.i(TAG, "Iteration $index: all $paywallCount paywalls Ready in ${allReadyMs}ms")
            IterationResult(
                index = index,
                cold = index == 0,
                configureMs = configureMs,
                paywallCount = paywallCount,
                allReadyMs = allReadyMs,
                preloadCompleteEventMs = preloadCompleteEventMs,
                perPaywallReadyMs = readyAt,
            )
        }
    }

    /**
     * All paywall views currently held by the SDK's view store.
     *
     * [Superwall.dependencyContainer] is internal to the SDK module, so the one
     * reflective hop below reads the backing field; everything after that
     * ([DependencyContainer.makeViewStore], [ViewStorage.views],
     * [PaywallView.state]) is public API.
     */
    private fun paywallViews(): List<PaywallView> {
        val field =
            Superwall::class.java.getDeclaredField("_dependencyContainer").apply {
                isAccessible = true
            }
        val container = field.get(Superwall.instance) as DependencyContainer
        return container
            .makeViewStore()
            .views.values
            .filterIsInstance<PaywallView>()
    }

    private fun writeResults(
        context: Context,
        results: List<IterationResult>,
    ) {
        val allReady = results.map { it.allReadyMs }
        val warm = results.drop(1).map { it.allReadyMs }
        val mean = allReady.average()
        val stdDev =
            if (allReady.size > 1) {
                kotlin.math.sqrt(allReady.sumOf { (it - mean) * (it - mean) } / (allReady.size - 1))
            } else {
                0.0
            }

        val json =
            JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("benchmark", "paywall-preload")
                put("tier", deviceTier)
                put("runIndex", runIndex)
                put("placements", if (placements.isEmpty()) JSONObject.NULL else placements.sorted().joinToString(","))
                put("apiKey", Keys.CONSTANT_API_KEY)
                put("timestampMs", System.currentTimeMillis())
                put(
                    "device",
                    JSONObject().apply {
                        put("model", Build.MODEL)
                        put("sdkInt", Build.VERSION.SDK_INT)
                        put("fingerprint", Build.FINGERPRINT)
                        put("cpuCores", Runtime.getRuntime().availableProcessors())
                        // What the SDK itself thinks this device is — lets CI verify the
                        // emulator config really lands in the intended LOW/MID/HIGH tier.
                        put(
                            "sdkClassifiedTier",
                            runCatching {
                                DeviceClassifier(DefaultClassifierDataFactory { context })
                                    .deviceTier()
                                    .raw
                            }.getOrDefault("unknown"),
                        )
                    },
                )
                put(
                    "iterations",
                    JSONArray().apply {
                        results.forEach { r ->
                            put(
                                JSONObject().apply {
                                    put("index", r.index)
                                    put("cold", r.cold)
                                    put("configureMs", r.configureMs)
                                    put("paywallCount", r.paywallCount)
                                    put("allReadyMs", r.allReadyMs)
                                    put("preloadCompleteEventMs", r.preloadCompleteEventMs ?: JSONObject.NULL)
                                    put("perPaywallReadyMs", JSONObject(r.perPaywallReadyMs as Map<*, *>))
                                },
                            )
                        }
                    },
                )
                put(
                    "stats",
                    JSONObject().apply {
                        put("meanMs", mean)
                        put("medianMs", median(allReady))
                        put("minMs", allReady.min())
                        put("maxMs", allReady.max())
                        put("stdDevMs", stdDev)
                        put("coefficientOfVariationPct", if (mean > 0) stdDev / mean * 100.0 else 0.0)
                        put("coldMs", results.first().allReadyMs)
                        put("warmMeanMs", if (warm.isEmpty()) JSONObject.NULL else warm.average())
                        put("iterationCount", results.size)
                    },
                )
            }

        val fileName = "preload-benchmark-${deviceTier.lowercase()}-run$runIndex.json"
        val content = json.toString(2)

        // Shared Downloads collection: survives the post-test uninstall and is
        // reachable with a plain `adb pull /sdcard/Download/superwall-benchmark`.
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$RESULTS_DIR")
            }
        val uri =
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create benchmark result in MediaStore")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }

        Log.i(TAG, "Wrote benchmark results to Download/$RESULTS_DIR/$fileName")
        Log.i(TAG, content)
    }

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }
}
