package com.superwall.superapp

import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.identity.identify
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.models.paywall.PaywallProducts
import com.superwall.sdk.paywall.presentation.internal.dismiss
import kotlinx.coroutines.delay

class UITestHandler {
    companion object {
        var test0Info = UITestInfo(
            0,
            "Uses the identify function. Should see the name 'Jack' in the paywall."
        )
        suspend fun test0() {
            // TODO: The name doesn't display
            Superwall.instance.identify(userId = "test0")
            Superwall.instance.setUserAttributes(attributes = mapOf("first_name" to "Jack"))
            Superwall.instance.register(event = "present_data")
        }

        var test1Info = UITestInfo(
            1,
            "Uses the identify function. Should see the name 'Kate' in the paywall."
        )
        suspend fun test1() {
            // TODO: The name doesn't display
            // Set identity
            Superwall.instance.identify(userId = "test1a")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            // Set new identity
            Superwall.instance.identify(userId = "test1b")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Kate"))
            Superwall.instance.register(event = "present_data")
        }

        var test2Info = UITestInfo(
            2,
            "Calls `reset()`. No first name should be displayed."
        )
        suspend fun test2() {
            // Set identity
            Superwall.instance.identify(userId = "test2")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            Superwall.instance.reset()
            Superwall.instance.register(event = "present_data")
        }

        var test3Info = UITestInfo(
            3,
            "Calls `reset()` multiple times. No first name should be displayed."
        )
        suspend fun test3() {
            // Set identity
            Superwall.instance.identify(userId = "test3")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            Superwall.instance.reset()
            Superwall.instance.reset()
            Superwall.instance.register(event = "present_data")
        }

        var test4Info = UITestInfo(
            4,
            "This paywall will open with a video playing that shows a 0 in the video at" +
                    "t0 and a 2 in the video at t2. It will close after 4 seconds. A new paywall " +
                    "will be presented 1 second after close. This paywall should have a video " +
                    "playing and should be started from the beginning with a 0 on the screen. " +
                    "Only a presentation delay of 1 sec as the paywall should already be loaded " +
                    "and we want to capture the video as quickly as possible."
        )

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

        var test5Info = UITestInfo(
            5,
            "Show paywall with override products. Paywall should appear with 2 products:" +
                    "1 monthly at \$12.99 and 1 annual at \$99.99."
        )

        suspend fun test5() {
            // TODO: Need to get some products from google play console and substitute in.
        }

        var test6Info = UITestInfo(
            6,
            "Present the paywall."
        )

        suspend fun test6() {
            // TODO: This doesn't have the products that it should have - need to add to
            //  google play console
            Superwall.instance.register(event = "present_products")
        }

        var test7Info = UITestInfo(
            7,
            "Adds a user attribute to verify rule on `present_and_rule_user` presents: " +
                    "user.should_display == true and user.some_value > 12. Then remove those " +
                    "attributes and make sure it's not presented."
        )

        suspend fun test7() {
            // TODO: This crashes with no rule match
            Superwall.instance.identify(userId = "test7")
            Superwall.instance.setUserAttributes(
                mapOf(
                    "first_name" to "Charlie",
                    "should_display" to true,
                    "some_value" to 14
                )
            )
            Superwall.instance.register(event = "present_and_rule_user")

            delay(8000)
            Superwall.instance.dismiss()

            // Remove those attributes.
            Superwall.instance.setUserAttributes(
                mapOf(
                    "should_display" to null,
                    "some_value" to null
                )
            )
            Superwall.instance.register(event = "present_and_rule_user")
        }

        var test8Info = UITestInfo(
            8,
            "Adds a user attribute to verify rule on `present_and_rule_user` DOES NOT" +
                    "present: user.should_display == true and user.some_value > 12"
        )
        suspend fun test8() {
            // TODO: Crashes on no rule match
            Superwall.instance.identify(userId = "test7")
            Superwall.instance.setUserAttributes(
                mapOf(
                    "first_name" to "Charlie",
                    "should_display" to true,
                    "some_value" to 12
                )
            )
            Superwall.instance.register(event = "present_and_rule_user")
        }

        var test9Info = UITestInfo(
            9,
            "Present regardless of status"
        )
        suspend fun test9() {
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Active)
            Superwall.instance.register(event = "present_always")
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)
        }

        var test10Info = UITestInfo(
            10,
            "Paywall should appear with 2 products: 1 monthly at \$4.99 and 1 annual at " +
                    "\$29.99. After dismiss, paywall should be presented again with override " +
                    "products: 1 monthly at \$12.99 and 1 annual at \$99.99. After dismiss, paywall " +
                    "should be presented again with no override products. After dismiss, paywall " +
                    "should be presented one last time with no override products."
        )
        suspend fun  test10() {
            // TODO: Product substitution
        }

        var test11Info = UITestInfo(
            11,
            "Clear a specific user attribute."
        )
        suspend fun test11() {
            // TODO: USer attributes not set
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Claire" ))
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            Superwall.instance.setUserAttributes(mapOf("first_name" to null))
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            Superwall.instance.setUserAttributes(mapOf("first_name" to "Sawyer"))
            Superwall.instance.register(event = "present_data")
        }

        var test12Info = UITestInfo(
            12,
            "Test trigger: off"
        )
        suspend fun test12() {
            Superwall.instance.register(event = "keep_this_trigger_off")
        }

        var test13Info = UITestInfo(
            13,
            "Test trigger: not in the dashboard"
        )
        suspend fun test13() {
            Superwall.instance.register(event = "i_just_made_this_up_and_it_dne")
        }

        var test14Info = UITestInfo(
            14,
            "Test trigger: not-allowed standard event (paywall_close)"
        )
        suspend fun test14() {
            // Show a paywall
            Superwall.instance.register(event = "present_always")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()
        }

        var test15Info = UITestInfo(
            15,
            "Clusterfucks by Jake™"
        )
        suspend fun test15() {
            // TODO: Stop multiple paywalls from being presented at a time
            Superwall.instance.register(event = "present_always")
            Superwall.instance.register(
                event = "present_always",
                params = mapOf("some_param_1" to "hello")
            )
            Superwall.instance.register(event = "present_always")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            Superwall.instance.register(event = "present_always")
            Superwall.instance.identify(userId = "1111")
            Superwall.instance.register(event = "present_always")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            // TODO: Add handler to register
//
//        var handler = PaywallPresentationHandler()
//
//        var experimentId = ""
//        handler.onPresent { info in
//                experimentId = info.experiment?.id ?? ""
//            Superwall.instance.register(event = "present_always")
//        }
//        Superwall.instance.register(event = "present_always", handler = handler)
        }

        var test16Info = UITestInfo(
            16,
            "Present an alert on Superwall.presentedViewController from the onPresent" +
                    " callback"
        )
        suspend fun test16() {
            // TODO: Can't do this without a handler in register
        }

        var test17Info = UITestInfo(
            17,
            "Clusterfucks by Jake™"
        )
        suspend fun test17() {
            Superwall.instance.identify(userId = "test0")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            // Set identity
            Superwall.instance.identify(userId = "test2")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            // Reset the user identity
            Superwall.instance.reset()

            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            // Present paywall
            Superwall.instance.register(event = "present_always")
            Superwall.instance.register(
                event = "present_always",
                params = mapOf("some_param_1" to "hello")
            )
            Superwall.instance.register(event = "present_always")
        }

        var test18Info = UITestInfo(
            18,
            "Open In-App Safari view controller from manually presented paywall"
        )
        suspend fun test18() {
            // TODO: Needs getPaywall
        }

        var test19Info = UITestInfo(
            19,
            "Clusterfucks by Jake™"
        )
        suspend fun test19() {
            // Set identity
            Superwall.instance.identify(userId = "test19a")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            Superwall.instance.reset()
            Superwall.instance.reset()
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            // TODO: Implement getPresentationResult
            // Superwall.instance.getPresentationResult(forEvent = "present_and_rule_user")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            // Show a paywall
            Superwall.instance.register(event = "present_always")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            delay(2000)

            // Set identity
            Superwall.instance.identify(userId = "test19b")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            // Set new identity
            Superwall.instance.identify(userId = "test19c")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Kate"))
            Superwall.instance.register(event = "present_data")
        }

        var test20Info = UITestInfo(
            20,
            "Verify that external URLs can be opened in native Safari from paywall"
        )
        suspend fun test20() {
            // Present paywall with URLs
            Superwall.instance.register(event = "present_urls")

            // Need to manually tap the button here
        }

        var test21Info = UITestInfo(
            21,
            "Present the paywall and purchase; then make sure the paywall doesn't get " +
                    "presented again after the purchase"
        )
        suspend fun test21() {
            // TODO: Automate purchase
        }

        var test22Info = UITestInfo(
            22,
            "Track an event shortly after another one is beginning to present. The " +
                    "session should not be cancelled out."
        )
        suspend fun test22() {
            // TODO: This is skipped in the iOS SDK for now
        }

        var test23Info = UITestInfo(
            23,
            "Case: Unsubscribed user, register event without a gating handler\n" +
                    "Result: paywall should display"
        )
        suspend fun test23() {
            // Register event
            Superwall.instance.register(event = "register_nongated_paywall")
        }

        var test24Info = UITestInfo(
            24,
            "Case: Subscribed user, register event without a gating handler\n" +
                    "Result: paywall should NOT display"
        )
        suspend fun test24() {
            // Set user as subscribed
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Active)

            // Register event - paywall shouldn't appear.
            Superwall.instance.register(event = "register_nongated_paywall")

            delay(4000)
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)
        }

        // TODO: Test 25-32 require stuff that we don't have rn

        var test33Info = UITestInfo(
            33,
            "Call identify twice with the same ID before presenting a paywall"
        )
        suspend fun test33() {
            // Set identity
            Superwall.instance.identify(userId = "test33")
            Superwall.instance.identify(userId = "test33")

            Superwall.instance.register(event = "present_data")
        }

        var test34Info = UITestInfo(
            34,
            "Call reset while a paywall is displayed should not cause a crash"
        )
        suspend fun test34() {
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Call reset while it is still on screen
            Superwall.instance.reset()
        }

        // TODO: Test 35-48 require either getPaywall or a feature block

        var test62Info = UITestInfo(
            62,
            "Verify that an invalid URL like `#` doesn't crash the app"
        )
        suspend fun test62() {
            // Present paywall with URLs
            Superwall.instance.register(event = "present_urls")

            // Need to manually tap on the URL button
        }

        // TODO: Test 63 -  require getPaywall, feature block, delegate, and surveys.


        var test72Info = UITestInfo(
            72,
            "Check that calling identify restores the seed value. This is async and " +
                    "dependent on config so needs to sleep after calling identify."
        )
        suspend fun test72() {
            // TODO: This fails to have the same userId after resetting and identifying.
            Superwall.instance.identify(userId = "abc")

            delay(1000)

            var seedHolder = Superwall.instance.getUserAttributes()
            println(seedHolder)

            Superwall.instance.reset()

            Superwall.instance.identify(userId = "abc")

            delay(1000)

            seedHolder = Superwall.instance.getUserAttributes()

            println(seedHolder)
        }
    }
}