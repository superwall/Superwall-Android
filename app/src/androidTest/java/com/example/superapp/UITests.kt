package com.example.superapp

import androidx.test.core.app.ActivityScenario
import com.karumi.shot.ScreenshotTest
import com.superwall.superapp.MainActivity
import org.junit.Test

class UITests: ScreenshotTest {
//    @Test
//    fun theActivityIsShownProperly() {
//        val mainActivity = startMainActivity();
//        /*
//          * Take the actual screenshot. At the end of this call, the screenshot
//          * is stored on the device and the gradle plugin takes care of
//          * pulling it and displaying it to you in nice ways.
//          */
//        compareScreenshot(activity);
//    }

    @Test
    fun theActivityIsShownProperly() {
        // Launch the activity
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // After launching, we take a screenshot
            scenario.onActivity { activity ->
                compareScreenshot(activity)
            }
        }
    }
}

