package com.example.superapp

import androidx.test.core.app.ActivityScenario
import com.karumi.shot.ScreenshotTest
import com.superwall.superapp.MainActivity
import org.junit.Test

class UITests: ScreenshotTest {
    @Test
    fun test0() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            compareScreenshot(activity, name = "test0")
        }
    }


}

