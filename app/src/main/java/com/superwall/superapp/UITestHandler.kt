package com.superwall.superapp

import com.superwall.sdk.Superwall
import com.superwall.sdk.identity.identify
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.models.paywall.PaywallProducts
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.operators.getPaywallViewController
import kotlinx.coroutines.delay

class UITestHandler {
    /// Uses the identify function. Should see the name 'Jack' in the paywall.
    suspend fun test0() {
        // TODO: The name doesn't display
        Superwall.instance.identify(userId = "test0")
        Superwall.instance.setUserAttributes(attributes = mapOf("first_name" to "Jack"))
        Superwall.instance.register(event = "present_data")
    }


    /// Uses the identify function. Should see the name 'Kate' in the paywall.
    suspend fun test1() {
        // TODO: The name doesn't display
        // Set identity
        Superwall.instance.identify(userId = "test1a")
        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack" ))

        // Set new identity
        Superwall.instance.identify(userId = "test1b")
        Superwall.instance.setUserAttributes(mapOf("first_name" to "Kate" ))
        Superwall.instance.register(event = "present_data")
    }

    /// Calls `reset()`. No first name should be displayed.
    suspend fun test2() {
        // Set identity
        Superwall.instance.identify(userId = "test2")
        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack" ))

        Superwall.instance.reset()
        Superwall.instance.register(event = "present_data")
    }

    /// Calls `reset()` multiple times. No first name should be displayed.
    suspend fun test3() {
        // Set identity
        Superwall.instance.identify(userId = "test3")
        Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack" ))

        Superwall.instance.reset()
        Superwall.instance.reset()
        Superwall.instance.register(event = "present_data")
    }

    /// This paywall will open with a video playing that shows a 0 in the video at t0 and a 2 in the video
    /// at t2. It will close after 4 seconds. A new paywall will be presented 1 second after close.
    /// This paywall should have a video playing and should be started from the beginning with a 0 on
    /// the screen. Only a presentation delay of 1 sec as the paywall should already be loaded and
    /// we want to capture the video as quickly as possible.
    suspend fun test4() {
        // Present the paywall.
        Superwall.instance.register(event = "present_video")

        // Dismiss after 4 seconds
        delay(4000)
        Superwall.instance.dismiss()

        // Present again after 1 second
        delay(1000)
        Superwall.instance.register(event = "present_video")
    }

    /// Show paywall with override products. Paywall should appear with 2 products: 1 monthly at
    /// $12.99 and 1 annual at $99.99.
    suspend fun test5() {
        // TODO: Need to get some products from google play console and substitute in.
    }

    // Present the paywall.
    suspend fun test6() {
        // TODO: This doesn't have the products that it should have - need to add to
        //  google play console
        Superwall.instance.register(event = "present_products")
    }

    // Adds a user attribute to verify rule on `present_and_rule_user`
    // presents: user.should_display == true and user.some_value > 12. Then remove
    // those attributes and make sure it's not presented.
    suspend fun test7() {
        // TODO: This crashes with no rule match
        Superwall.instance.identify(userId = "test7")
        Superwall.instance.setUserAttributes(mapOf(
            "first_name" to "Charlie",
            "should_display" to true,
            "some_value" to 14
        ))
        Superwall.instance.register(event = "present_and_rule_user")

        delay(8000)
        Superwall.instance.dismiss()

        // Remove those attributes.
        Superwall.instance.setUserAttributes(mapOf(
            "should_display" to null,
            "some_value" to null
        ))
        Superwall.instance.register(event = "present_and_rule_user")
    }

    // Adds a user attribute to verify rule on `present_and_rule_user` DOES NOT
    // present: user.should_display == true and user.some_value > 12
    suspend fun test8() {
        // TODO: Crashes on no rule match
        Superwall.instance.identify(userId = "test7")
        Superwall.instance.setUserAttributes(mapOf(
            "first_name" to "Charlie",
            "should_display" to true,
            "some_value" to 12
        ))
        Superwall.instance.register(event = "present_and_rule_user")
    }
}