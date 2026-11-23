package com.example.superapp.utils

import android.os.Build
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * A device whose screenshots are comparable with one set of baselines.
 *
 * Baselines live in `app/src/androidTest/screenshots` and are named `<prefix>_TestCase_<n>…png`.
 * Full-screen captures are only comparable between devices with the same screen size, density,
 * system bar heights and Android version, so every profile has its own set.
 */
enum class ScreenshotProfile(
    /** Prefix of this profile's baseline files. */
    val prefix: String,
    val apiLevel: Int,
    /** Display the device is forced to before a test, or null to leave the device's own. */
    val display: Display?,
) {
    /**
     * Any emulator. [DeviceStateRule] forces the Pixel 7 screen size and density, so the
     * emulator's own hardware profile doesn't matter. This is the set to use locally and on PRs.
     */
    EMULATOR("Emulator_Pixel_7_API_33", apiLevel = 33, display = Display(1080, 2400, 420)),

    /** A physical Pixel 7, which is what Firebase Test Lab runs (`panther`). */
    PIXEL_7("Google_Pixel_7", apiLevel = 33, display = null),
    ;

    data class Display(
        val width: Int,
        val height: Int,
        val density: Int,
    )
}

object ScreenshotDevice {
    /**
     * Instrumentation argument that picks the profile by name (`emulator` or `pixel_7`), for a
     * device that isn't recognised. Pass it with
     * `-Pandroid.testInstrumentationRunnerArguments.screenshotProfile=emulator`.
     */
    const val PROFILE_ARG = "screenshotProfile"

    val isEmulator: Boolean by lazy {
        Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm", "redroid") ||
            Build.FINGERPRINT.contains("/sdk_") ||
            Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.startsWith("sdk_") ||
            Build.MODEL.startsWith("sdk_") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Cuttlefish")
    }

    val profile: ScreenshotProfile by lazy {
        val requested = InstrumentationRegistry.getArguments().getString(PROFILE_ARG)
        when {
            requested != null ->
                ScreenshotProfile.values().firstOrNull { it.name.equals(requested, ignoreCase = true) }
                    ?: error(
                        "Unknown $PROFILE_ARG '$requested'. Use one of " +
                            ScreenshotProfile.values().joinToString { it.name.lowercase() },
                    )
            isEmulator -> ScreenshotProfile.EMULATOR
            Build.DEVICE == "panther" -> ScreenshotProfile.PIXEL_7
            else ->
                error(
                    "There are no screenshot baselines for ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}). " +
                        "Run the screenshot tests on an API 33 emulator, " +
                        "or pass -Pandroid.testInstrumentationRunnerArguments.$PROFILE_ARG=emulator to force this " +
                        "device to the emulator's screen size. See app/src/androidTest/README.md.",
                )
        }
    }

    /**
     * Whether this run records new baselines, which the Dropshots Gradle plugin signals with a
     * marker file on the device (`record<Variant>Screenshots`).
     */
    fun isRecording(): Boolean {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "screenshots/$pkg/.isRecordingScreenshots").exists()
    }

    /**
     * Fails with what to do about it when this device runs another Android version than the
     * baselines were recorded on. Recording is let through, so a profile can be moved to a new
     * version by re-recording it.
     */
    fun requireComparable(recording: Boolean = isRecording()) {
        val profile = profile
        if (recording || Build.VERSION.SDK_INT == profile.apiLevel) return
        error(
            "The ${profile.prefix} baselines were recorded on API ${profile.apiLevel}, but this device runs " +
                "API ${Build.VERSION.SDK_INT}. System bars and WebView rendering differ between versions, " +
                "so the comparison would fail. Use an API ${profile.apiLevel} device.",
        )
    }
}
