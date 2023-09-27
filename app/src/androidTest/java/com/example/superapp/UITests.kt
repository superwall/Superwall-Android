package com.example.superapp

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.launch
import com.karumi.shot.ScreenshotTest
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.identity.identify
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.paywall.presentation.register
import com.superwall.superapp.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Test
import org.junit.Before
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.seconds

class UITests {
    private val testDispatcher = TestCoroutineDispatcher()
    private val testScope = TestCoroutineScope(testDispatcher)

    @Before
    fun setup() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            Superwall.configure(activity, "pk_d1f0959f70c761b1d55bb774a03e22b2b6ed290ce6561f85")

            // TODO: Fix this so we don't need to make the user set this
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)
        }
    }

    @Test
    fun exampleTest() = runTest {
        val deferred = async {
            delay(100000)
            async {
                delay(100000)
            }.await()
        }

        // Test 0
        // TODO: Identify causes a crash
//            Superwall.instance.identify("test0")
        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
        Superwall.instance.register("campaign_trigger")

        deferred.await() // result available immediately
    }

    @Test fun test0fix() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            // Test 0
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
            Superwall.instance.register("campaign_trigger")

            // Delay without blocking
            testScope.launch {
                delay(10000)
//                compareScreenshot(activity, name = "test0")
            }
        }
    }

    /// Uses the identify function. Should see the name 'Jack' in the paywall.
    @Test fun test0() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            runBlocking(Dispatchers.Default) {
                launch(Dispatchers.IO) {
                    // Test 0
                    // TODO: Identify causes a crash
//            Superwall.instance.identify("test0")
                    Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
                    Superwall.instance.register("campaign_trigger")
                }

                delay(10000000)

                print("Test")
            }
//
//            compareScreenshot(activity, name = "test0")
        }
    }
}

// Extensions for UITests.kt
fun UITests.compareValue(value: String, name: String) {

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