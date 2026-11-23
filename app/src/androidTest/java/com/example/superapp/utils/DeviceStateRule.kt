package com.example.superapp.utils

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Puts the device into the state the screenshot baselines were recorded in, so a test doesn't
 * depend on how the emulator was created or what was changed on it since.
 *
 * Every device gets font scale 1, light theme and no animations. A device on a profile with a
 * [ScreenshotProfile.display] (the emulator) is also forced to that screen size and density and to
 * gesture navigation; the size and density are put back afterwards.
 */
class DeviceStateRule(
    private val profile: () -> ScreenshotProfile = { ScreenshotDevice.profile },
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val device = UiDevice.getInstance(getInstrumentation())
                val display = profile().display
                var displayOverridden = false
                try {
                    device.shell("settings put system font_scale 1.0")
                    device.shell("cmd uimode night no")
                    listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach {
                        device.shell("settings put global $it 0")
                    }
                    if (display != null) {
                        device.shell("cmd overlay enable-exclusive --category $GESTURE_NAV_OVERLAY")
                        val size = "${display.width}x${display.height}"
                        val sizeNow = device.shell("wm size")
                        val densityNow = device.shell("wm density")
                        // The last line is the size in use: "Override size" when there is one,
                        // "Physical size" otherwise. Density is reported the same way.
                        if (!sizeNow.trim().lines().last().endsWith(size)) {
                            device.shell("wm size $size")
                            displayOverridden = true
                        }
                        if (!densityNow.trim().lines().last().endsWith(" ${display.density}")) {
                            device.shell("wm density ${display.density}")
                            displayOverridden = true
                        }
                    }
                    device.shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                    device.waitForIdle()
                    base.evaluate()
                } finally {
                    if (displayOverridden) {
                        device.shell("wm size reset")
                        device.shell("wm density reset")
                    }
                }
            }
        }

    private fun UiDevice.shell(command: String): String =
        try {
            executeShellCommand(command)
        } catch (e: Exception) {
            // A setting a device doesn't have shouldn't fail the test; the screenshot will.
            Log.w("DeviceStateRule", "'$command' failed: ${e.message}")
            ""
        }

    private companion object {
        const val GESTURE_NAV_OVERLAY = "com.android.internal.systemui.navbar.gestural"
    }
}
