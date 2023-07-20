package com.example.superapp

import androidx.test.core.app.ActivityScenario
import com.karumi.shot.ScreenshotTest
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.superapp.MainActivity
import org.junit.Test

class UITests: ScreenshotTest {
    @Test
    fun test0() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->


            compareScreenshot(activity, name = "test0")
        }

        // UI-Tests
        Superwall.configure(this, "pk_5f6d9ae96b889bc2c36ca0f2368de2c4c3d5f6119aacd3d2")

        // TODO: Fix this so we don't need to make the user set this
        Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)

        // Test 0
        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
        Superwall.instance.register("present_data")
    }


}

//        // Test 0
//        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
//        Superwall.instance.register("present_data")



// Test 1
// Uses the identify function. Should see the name 'Kate' in the paywall.

//        // Set identity
//        Superwall.instance.identify("test1a")
//        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
//
//        // Set new identity
//        Superwall.instance.identify("test1b")
//        Superwall.instance.setUserAttributes(mapOf("first_name" to "Kate"))
//        Superwall.instance.register("present_data")


// Test 2
// Calls `reset()`. No first name should be displayed.
//        println("!! main identify - start")
//        Superwall.instance.identify("test2")
//        println("!! main identify - end")
//        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

// Set new identity
//        println("!! main reset - start")
//        Superwall.instance.reset()
//        println("!! main reset - end")
//        Superwall.instance.register("present_data")







// Delay the presentation of the paywall by 5 seconds
//        android.os.Handler().postDelayed({
//            Superwall.instance.track(EventData("test_event", mapOf("test_key" to "test_value")))
//        }, 200)