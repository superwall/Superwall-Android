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

@OptIn(ExperimentalCoroutinesApi::class)
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

    private val runIndex = args.getString("benchmarkRunIndex")?.toIntOrNull() ?: 1

    private val configureTimeoutSec = args.getString("benchmarkConfigureTimeoutSec")?.toLongOrNull() ?: 120L

    private val placements =
        args
            .getString("benchmarkPlacements")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private val events =
        MutableSharedFlow<Pair<SuperwallEvent, Long>>(replay = 64, extraBufferCapacity = 512)

    private val delegate =
        object : SuperwallDelegate {
            override fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
                events.tryEmit(eventInfo.event to SystemClock.elapsedRealtime())
            }
        }

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

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().build())

        val scenario = ActivityScenario.launch(BenchmarkForegroundActivity::class.java)

        val results = mutableListOf<IterationResult>()
        try {
            repeat(iterations) { index ->
                results += runBlocking { runIteration(app, index) }
                if (index < iterations - 1) {
                    Superwall.teardown()
                    runBlocking { delay(1.seconds) }
                }
            }
        } finally {
            scenario.close()
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
                    logging.level = LogLevel.debug
                    if (BuildConfig.BENCHMARK_TEST_MODE) {
                        testModeBehavior = TestModeBehavior.ALWAYS
                    }
                    paywalls =
                        PaywallOptions().apply {
                            shouldPreload = false
                        }
                },
        )
        Superwall.instance.delegate = delegate
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
