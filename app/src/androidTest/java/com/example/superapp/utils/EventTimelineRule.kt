package com.example.superapp.utils

import android.os.Environment
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.superapp.test.UITestInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

private const val TIMELINE_DIR = "superwall-event-timelines"
private const val TAG = "EventTimeline"

/**
 * Writes the event timeline for a [UITestInfo] to a JSON file on device storage.
 *
 * Output directory: /sdcard/Android/data/<pkg>/files/Download/superwall-event-timelines/
 * (app-scoped external storage — no runtime permission required on API 30+).
 *
 * Pull results with:
 *   adb pull /sdcard/Android/data/<pkg>/files/Download/superwall-event-timelines/ app/build/outputs/event-timelines/
 */
fun writeTimelineToFile(
    testInfo: UITestInfo,
    testClassName: String,
    testMethodName: String,
) {
    val timeline = testInfo.timeline
    if (timeline.allEvents().isEmpty()) return

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir
    val dir = File(baseDir, TIMELINE_DIR)
    dir.mkdirs()

    val fileName = "${testClassName}_${testMethodName}.json"
    val file = File(dir, fileName)

    val json = JSONObject().apply {
        put("testClass", testClassName)
        put("testMethod", testMethodName)
        put("testNumber", testInfo.number)
        put("testDescription", testInfo.description)
        put("totalDurationMs", timeline.totalDuration().inWholeMilliseconds)
        put("eventCount", timeline.allEvents().size)
        put("events", JSONArray().apply {
            timeline.toSerializableList().forEach { map ->
                put(JSONObject().apply {
                    map.forEach { (k, v) ->
                        when (v) {
                            is Map<*, *> -> put(k, JSONObject(v as Map<*, *>))
                            else -> put(k, v)
                        }
                    }
                })
            }
        })
    }

    file.writeText(json.toString(2))
    Log.i(TAG, "Wrote timeline to ${file.absolutePath} (${timeline.allEvents().size} events)")
}

/**
 * Overload that derives the filename from [UITestInfo] directly.
 * Used by [screenshotFlow] where no JUnit [Description] is available.
 */
fun writeTimelineToFile(testInfo: UITestInfo) {
    writeTimelineToFile(
        testInfo,
        testClassName = "Test${testInfo.number}",
        testMethodName = testInfo.testCaseType.titleText(testInfo.number).replace(" ", "_"),
    )
}

/**
 * JUnit TestWatcher that automatically writes the [com.superwall.superapp.test.EventTimeline]
 * from a [UITestInfo] to a JSON file on the device after each test finishes (pass or fail).
 * Uses JUnit's [Description] for reliable test class/method naming.
 *
 * Add to any test class:
 * ```
 * @get:Rule
 * val timelineRule = EventTimelineRule { currentTestInfo }
 * ```
 */
class EventTimelineRule(
    private val testInfoProvider: () -> UITestInfo?,
) : TestWatcher() {
    override fun finished(description: Description) {
        val testInfo = testInfoProvider() ?: return
        writeTimelineToFile(
            testInfo,
            description.testClass.simpleName,
            description.methodName,
        )
    }
}
