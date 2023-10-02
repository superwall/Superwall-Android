package com.superwall.superapp.test

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent.DeepLink
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.identity.identify
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.misc.AlertControllerFactory
import com.superwall.sdk.paywall.presentation.PaywallPresentationHandler
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.paywall.presentation.get_paywall.getPaywall
import com.superwall.sdk.paywall.presentation.get_presentation_result.getPresentationResult
import com.superwall.sdk.paywall.presentation.register
import com.superwall.sdk.paywall.vc.SuperwallPaywallActivity
import com.superwall.superapp.ComposeActivity
import kotlinx.coroutines.delay

class UITestHandler {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context

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
            // TODO: The name doesn't get set to begin with so isn't an accurate test.
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
                    "playing and should be started from the beginning with a 0 on the screen. "
        )

        suspend fun test4() {
            // Present the paywall.
            Superwall.instance.register(event = "present_video")

            // Dismiss after 4 seconds
            delay(4000)
            Superwall.instance.dismiss()
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
            "Paywall should appear with 2 products: 1 monthly at \$4.99 and 1 annual at" +
                    " \$29.99."
        )

        suspend fun test6() {
            // TODO: This doesn't have the products that it should have - need to add to
            //  google play console
            Superwall.instance.register(event = "present_products")
        }

        var test7Info = UITestInfo(
            7,
            "Adds a user attribute to verify rule on `present_and_rule_user` presents: " +
                    "user.should_display == true and user.some_value > 12. Then dismisses and removes " +
                    "those attributes. Make sure it's not presented."
        )

        suspend fun test7() {
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
            "Adds a user attribute to verify rule on `present_and_rule_user`. Verify it" +
                    " DOES NOT present: user.should_display == true and user.some_value > 12"
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
            "Sets subs status to active, paywall should present regardless of this," +
                    " then it sets the status back to inactive."
        )

        suspend fun test9() {
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.ACTIVE)
            Superwall.instance.register(event = "present_always")
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)
        }

        var test10Info = UITestInfo(
            10,
            "Paywall should appear with 2 products: 1 monthly at \$4.99 and 1 annual at " +
                    "\$29.99. After dismiss, paywall should be presented again with override " +
                    "products: 1 monthly at \$12.99 and 1 annual at \$99.99. After dismiss, paywall " +
                    "should be presented again with no override products. After dismiss, paywall " +
                    "should be presented one last time with no override products."
        )

        suspend fun test10() {
            // TODO: Product substitution
        }

        var test11Info = UITestInfo(
            11,
            "Paywall should present with the name Claire. Then it should dismiss after" +
                    "8 seconds and present again without any name. Then it should present again" +
                    " with the name Sawyer."
        )
        suspend fun test11() {
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Claire"))
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()
            Superwall.instance.setUserAttributes(mapOf("first_name" to null))
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Sawyer"))
            Superwall.instance.register(event = "present_data")
        }

        var test12Info = UITestInfo(
            12,
            "Test trigger: off. Paywall shouldn't present. Should print eventNotFound."
        )

        suspend fun test12() {
            Superwall.instance.register(event = "keep_this_trigger_off")
        }

        var test13Info = UITestInfo(
            13,
            "Test trigger: not in the dashboard. Paywall shouldn't present."
        )

        suspend fun test13() {
            Superwall.instance.register(event = "i_just_made_this_up_and_it_dne")
        }

        var test14Info = UITestInfo(
            14,
            "Presents the paywall and then dismisses after 8 seconds. The paywall shouldn't " +
                    "display based on a paywall_close event."
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
            "Clusterfucks by Jake™. One paywall should present, then it should disappear" +
                    " then another paywall should present and disappear. Then a third should present. " +
                    "Verify that the console output contains a non-null experimentId. After the third " +
                    "presentation, manually close the paywall. During this test only 3 paywalls should " +
                    "event present. If more than 3 present, this test has failed."
        )

        suspend fun test15() {
            Superwall.instance.register(event = "present_always")
            Superwall.instance.register(
                event = "present_always",
                params = mapOf("some_param_1" to "hello")
            )
            Superwall.instance.register(event = "present_always")

            delay(5000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            Superwall.instance.register(event = "present_always")
            Superwall.instance.identify(userId = "1111")
            Superwall.instance.register(event = "present_always")

            delay(5000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            var handler = PaywallPresentationHandler()
            handler.onPresent { info ->
                val experimentId = info.experiment?.id ?: ""
                println("!!! TEST 15 !!! experimentId: $experimentId")
                Superwall.instance.register(event = "present_always")
            }

            Superwall.instance.register(event = "present_always", handler = handler)
        }

        var test16Info = UITestInfo(
            16,
            "Present an alert on Superwall.presentedViewController from the onPresent" +
                    " callback"
        )

        suspend fun test16() {
            val paywallPresentationHandler = PaywallPresentationHandler()
            paywallPresentationHandler.onPresent { info ->
                val alertController = AlertControllerFactory.make(
                    context = context,
                    title = "Paywall presented",
                    message = "The paywall did present",
                    actionTitle = "Ok"
                )
                alertController.show()
            }

            Superwall.instance.register(
                "present_always",
                null,
                paywallPresentationHandler
            )
        }

        var test17Info = UITestInfo(
            17,
            "Clusterfucks by Jake™. This presents a paywall with the name Jack. Then it " +
                    "dismisses after 8s. Then another paywall will present with no name. Then" +
                    " the paywall will dismiss after 8s and one more paywall will display. After " +
                    "the third presentation, manually close the paywall. During this test only 3 " +
                    "paywalls should event present. If more than 3 present, this test has failed."
        )

        suspend fun test17() {
            Superwall.instance.identify(userId = "test0")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            // Set identity
            Superwall.instance.identify(userId = "test2")
            Superwall.instance.setUserAttributes(mapOf("first_name" to "Jack"))

            // Reset the user identity
            Superwall.instance.reset()

            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

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
            "Open In-App browser from a manually presented paywall. Once the in-app " +
                    "browser opens, close it, and verify that the paywall is still showing."
        )
        suspend fun test18() {
            // Create a mock paywall view controller
            val delegate = MockPaywallViewControllerDelegate()

            // Get the paywall view controller instance
            val viewController = Superwall.instance.getPaywall(event = "present_urls", delegate = delegate)

            // Present using the convenience `SuperwallPaywallActivity` activity and verify test case.
            SuperwallPaywallActivity.startWithView(context = context, view = viewController)
        }

        var test19Info = UITestInfo(
            19,
            "Clusterfucks by Jake™. Ths presents a paywall with no name. Then it dismisses" +
                    " after 8s. Then it presents again with no name, dismisses, and finally presents " +
                    "with the name Kate."
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

            Superwall.instance.getPresentationResult(event = "present_and_rule_user")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

            // Show a paywall
            Superwall.instance.register(event = "present_always")

            delay(8000)

            // Dismiss any view controllers
            Superwall.instance.dismiss()

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
            "Verify that external URLs can be opened in native browser from paywall. When" +
                    " the paywall opens, tap \"Perform\" for \"Open in Safari\". Afterwards, go back " +
                    "and verify the paywall is still displayed."
        )

        suspend fun test20() {
            // Present paywall with URLs
            Superwall.instance.register("present_urls")
        }

        var test21Info = UITestInfo(
            21,
            "Present the paywall and make a purchase. After purchasing completes and " +
                    "the paywall dismisses, attempt to launch again. The paywall should NOT appear."
        )

        suspend fun test21() {
            Superwall.instance.register(event = "present_data")
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
                    "Result: paywall should NOT display. Resets subscription status to inactive " +
                    "4s later."
        )

        suspend fun test24() {
            // Set user as subscribed
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.ACTIVE)

            // Register event - paywall shouldn't appear.
            Superwall.instance.register(event = "register_nongated_paywall")

            delay(4000)
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)
        }

        var test25Info = UITestInfo(
            25,
            "Present the paywall and make a purchase. After 12s it'll try to present a " +
                    "paywall again. It shouldn't present. These register calls don't have a feature gate."
        )

        suspend fun test25() {
            Superwall.instance.register(event = "register_nongated_paywall")

            // Manually purchase

            delay(12000)

            // Try to present paywall again
            Superwall.instance.register(event = "register_nongated_paywall")
        }

        var test26Info = UITestInfo(
            26,
            "Registers an event with a gating handler. The paywall should display, you should " +
                    "NOT see an alert when you close the paywall."
        )
        suspend fun test26() {
            Superwall.instance.register(event = "register_gated_paywall") {
                val alertController = AlertControllerFactory.make(
                    context = context,
                    title = "Feature Launched",
                    message = "The feature block was called",
                    actionTitle = "Ok"
                )
                alertController.show()
            }
        }

        var test27Info = UITestInfo(
            27,
            "Tapping the button shouldn't present the paywall but should launch the " +
                    "feature block - an alert should present."
        )
        suspend fun test27() {
            var currentSubscriptionStatus = Superwall.instance.subscriptionStatus.value

            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.ACTIVE)

            Superwall.instance.register(event = "register_gated_paywall") {
                val alertController = AlertControllerFactory.make(
                    context = context,
                    title = "Feature Launched",
                    message = "The feature block was called",
                    actionTitle = "Ok"
                )
                alertController.show()
            }

            delay(4000)
            Superwall.instance.setSubscriptionStatus(currentSubscriptionStatus)

        }

        var test28Info = UITestInfo(
            28,
            "Should print out \"Paywall(experiment...)\"."
        )
        suspend fun test28() {
            val result = Superwall.instance.getPresentationResult("present_data")
            println("!!! TEST 28 !!! $result")
        }

        var test29Info = UITestInfo(
            29,
            "Should print out \"noRuleMatch\"."
        )
        suspend fun test29() {
            Superwall.instance.setUserAttributes(mapOf("should_display" to null, "some_value" to null))
            val result = Superwall.instance.getPresentationResult("present_and_rule_user")
            println("!!! TEST 29 !!! $result")
        }

        var test30Info = UITestInfo(
            30,
            "Should print out \"eventNotFound\"."
        )
        suspend fun test30() {
            val result = Superwall.instance.getPresentationResult("some_random_not_found_event")
            println("!!! TEST 30 !!! $result")
        }

        var test31Info = UITestInfo(
            31,
            "Should print out \"holdout\"."
        )
        suspend fun test31() {
            val result = Superwall.instance.getPresentationResult("holdout")
            println("!!! TEST 31 !!! $result")
        }

        var test32Info = UITestInfo(
            32,
            "This sets the subscription status active, prints out \"userIsSubscribed\" " +
                    "and then returns subscription status to inactive."
        )
        suspend fun test32() {
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.ACTIVE)
            val result = Superwall.instance.getPresentationResult("present_data")
            println("!!! TEST 32 !!! $result")
            Superwall.instance.setSubscriptionStatus(SubscriptionStatus.INACTIVE)

        }

        var test33Info = UITestInfo(
            33,
            "Calls identify twice with the same ID before presenting a paywall"
        )

        suspend fun test33() {
            // Set identity
            Superwall.instance.identify(userId = "test33")
            Superwall.instance.identify(userId = "test33")

            Superwall.instance.register(event = "present_data")
        }

        var test34Info = UITestInfo(
            34,
            "Call reset 8s after a paywall is presented – should not cause a crash."
        )

        suspend fun test34() {
            Superwall.instance.register(event = "present_data")

            delay(8000)

            // Call reset while it is still on screen
            Superwall.instance.reset()
        }

        var test35Info = UITestInfo(
            35,
            "Purchase from the paywall and then check that after the purchase has finished " +
                    "the result type `purchased` is printed to the console. The paywall should dismiss." +
                    " After doing this, try test 37"
        )
        suspend fun test35() {
            // Create a mock paywall view controller
            val delegate = MockPaywallViewControllerDelegate()
            delegate.paywallViewControllerDidFinish { paywallViewController, paywallResult, shouldDismiss ->
                println("!!! TEST 35 !!! Result: $paywallResult, shouldDismiss: $shouldDismiss, paywallVc: $paywallViewController")
            }

            // Get the paywall view controller instance
            val viewController = Superwall.instance.getPaywall(event = "present_data", delegate = delegate)

            // Present using the convenience `SuperwallPaywallActivity` activity and verify test case.
            SuperwallPaywallActivity.startWithView(context = context, view = viewController)
        }

        var test36Info = UITestInfo(
            36,
            "Close the paywall and check that after the purchase has finished \" " +
                    "\"the result type \"declined\" is printed to the console. The paywall should close."
        )
        suspend fun test36() {
            // Create a mock paywall view controller
            val delegate = MockPaywallViewControllerDelegate()
            delegate.paywallViewControllerDidFinish { paywallViewController, paywallResult, shouldDismiss ->
                println("!!! TEST 36 !!! Result: $paywallResult, shouldDismiss: $shouldDismiss, paywallVc: $paywallViewController")
            }

            // Get the paywall view controller instance
            val viewController = Superwall.instance.getPaywall(event = "present_data", delegate = delegate)

            // Present using the convenience `SuperwallPaywallActivity` activity and verify test case.
            SuperwallPaywallActivity.startWithView(context = context, view = viewController)
        }

        var test37Info = UITestInfo(
            37,
            "Need to have purchased a product before calling this test, then present the " +
                    "paywall and tap \"restore\". The paywall should dismiss and the the console should" +
                    "print the paywallResult as \"restored\"."
        )
        suspend fun test37() {
            // Create a mock paywall view controller
            val delegate = MockPaywallViewControllerDelegate()
            delegate.paywallViewControllerDidFinish { paywallViewController, paywallResult, shouldDismiss ->
                println("!!! TEST 37 !!! Result: $paywallResult, shouldDismiss: $shouldDismiss, paywallVc: $paywallViewController")
            }

            // Get the paywall view controller instance
            val viewController = Superwall.instance.getPaywall(event = "restore", delegate = delegate)

            // Present using the convenience `SuperwallPaywallActivity` activity and verify test case.
            SuperwallPaywallActivity.startWithView(context = context, view = viewController)
        }

        // TODO: Test 38 & 39, & 40 need to be able to present modally and swipe to dismiss implemented.
        // TODO: Tests 41 - 48 require a feature block

        // Warning: Change `subscribed` param to product id
        suspend fun executeRegisterFeatureClosureTest(subscribed: Boolean, gated: Boolean) {
            var currentSubscriptionStatus = Superwall.instance.subscriptionStatus.value

            if (subscribed) {
                // Set user subscribed
                Superwall.instance.setSubscriptionStatus(SubscriptionStatus.ACTIVE)
            }

            // Determine gating event
            val event = if (gated) {
                "register_gated_paywall"
            } else {
                "register_nongated_paywall"
            }

            val paywallPresentationHandler = PaywallPresentationHandler()
            paywallPresentationHandler.onError { error ->
                println("!!! ERROR HANDLER !!! $error")
            }

            Superwall.instance.register(event, null, paywallPresentationHandler) {
                val alertController = AlertControllerFactory.make(
                    context = context,
                    title = "Feature Launched",
                    message = "The feature block was called",
                    actionTitle = "Ok"
                )
                alertController.show()
            }

            delay(4000)

            if (subscribed) {
                // Reset status
                Superwall.instance.setSubscriptionStatus(currentSubscriptionStatus)
            }
        }

        var test41Info = UITestInfo(
            41,
            "Unable to fetch config, not subscribed, and not gated. First disable " +
                    "internet on device. You should not be subscribed. You SHOULD " +
                    "see !!! ERROR HANDLER !!! in the console and the alert should NOT show."
        )
        suspend fun test41() {
            executeRegisterFeatureClosureTest(subscribed = false, gated = false)
        }

        var test42Info = UITestInfo(
            42,
            "Unable to fetch config, not subscribed, and gated. First disable internet " +
                    "on device. You should not be subscribed.  You SHOULD " +
                    "see !!! ERROR HANDLER !!! in the console and the alert should NOT show."
        )
        suspend fun test42() {
            executeRegisterFeatureClosureTest(subscribed = false, gated = true)
        }

        var test43Info = UITestInfo(
            43,
            "Unable to fetch config, subscribed, and not gated. First disable internet on " +
                    "device. You should NOT see !!! ERROR HANDLER !!! in the console and the alert " +
                    "SHOULD show."
        )
        suspend fun test43() {
            executeRegisterFeatureClosureTest(subscribed = true, gated = false)
        }

        var test44Info = UITestInfo(
            44,
            "Unable to fetch config, subscribed, and gated. First disable internet on " +
                    "device. You should NOT see !!! ERROR HANDLER !!! in the console and the alert " +
                    "SHOULD show."
        )
        suspend fun test44() {
            executeRegisterFeatureClosureTest(subscribed = true, gated = true)
        }

        var test45Info = UITestInfo(
            45,
            "Fetched config, not subscribed, and not gated. The paywall should show. On " +
                    "paywall dismiss you should NOT see !!! ERROR HANDLER !!! in the console and the " +
                    "alert should show when you dismiss the paywall."
        )
        suspend fun test45() {
            executeRegisterFeatureClosureTest(subscribed = false, gated = false)
            delay(4000)
        }

        var test46Info = UITestInfo(
            46,
            "Fetched config, not subscribed, and gated. The paywall should show. You should " +
                    "NOT see !!! ERROR HANDLER !!! in the console and the alert should NOT show on " +
                    "paywall dismiss."
        )
        suspend fun test46() {
            executeRegisterFeatureClosureTest(subscribed = false, gated = true)
            delay(4000)
        }

        var test47Info = UITestInfo(
            47,
            "Fetched config, subscribed, and not gated. The paywall should NOT show. You " +
                    "should NOT see !!! ERROR HANDLER !!! in the console and the alert SHOULD show."
        )
        suspend fun test47() {
            executeRegisterFeatureClosureTest(subscribed = true, gated = false)
        }

        var test48Info = UITestInfo(
            48,
            "Fetched config, subscribed, and gated. The paywall should NOT show. You should" +
                    " NOT see !!! ERROR HANDLER !!! in the console and the alert SHOULD show."
        )
        suspend fun test48() {
            executeRegisterFeatureClosureTest(subscribed = true, gated = true)
            delay(4000)
        }

        // TODO: Tests 49 - 56

        var test57Info = UITestInfo(
            57,
            "NOTE: Must use Deep Link API key. Present paywall from implicit trigger: " +
                    "`deepLink_open`. Verify the `Deep link event received successfully.` in the" +
                    " console."
        )
        suspend fun test57() {
            // Create a mock Superwall delegate
            val delegate = MockSuperwallDelegate()

            // Set delegate
            Superwall.instance.delegate = delegate

            // Respond to Superwall events
            delegate.handleSuperwallEvent { eventInfo ->
                when (eventInfo.event) {
                    is DeepLink -> {
                        println("!!! TEST 57 !!! Result: Deep link event received successfully.")
                    }
                    else -> return@handleSuperwallEvent
                }
            }

            // Handle the URL
            val url = Uri.parse("superapp://mydeepLink?isDeepLink=true")
            val handled = Superwall.instance.handleDeepLink(url)
        }


        var test62Info = UITestInfo(
            62,
            "Verify that an invalid URL like `#` doesn't crash the app. Manually tap on" +
                    "the \"Open in-app #\" button."
        )

        suspend fun test62() {
            // Present paywall with URLs
            Superwall.instance.register(event = "present_urls")

        }

        var test63Info = UITestInfo(
            63,
            "Don't have an active subscription, present paywall, tap restore. Check " +
                    "the \"No Subscription Found\" alert pops up."
        )
        suspend fun test63() {
            // Create a mock paywall view controller
            val delegate = MockPaywallViewControllerDelegate()
            delegate.paywallViewControllerDidFinish { paywallViewController, paywallResult, shouldDismiss ->
                println("!!! TEST 37 !!! Result: $paywallResult, shouldDismiss: $shouldDismiss, paywallVc: $paywallViewController")
            }

            // Get the paywall view controller instance
            val viewController = Superwall.instance.getPaywall(event = "restore", delegate = delegate)

            // Present using the convenience `SuperwallPaywallActivity` activity and verify test case.
            SuperwallPaywallActivity.startWithView(context = context, view = viewController)
        }

        // TODO: Test 63 - 71 require getPaywall, feature block, delegate, and surveys.

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
            println("!!! seedHolder - 1: $seedHolder")

            Superwall.instance.reset()

            Superwall.instance.identify(userId = "abc")

            delay(1000)

            seedHolder = Superwall.instance.getUserAttributes()
            println("!!! seedHolder - 2: $seedHolder")

        }

        var test82Info = UITestInfo(
            82,
            "Verify that our pricing gets templated in correctly."
        )

        suspend fun test82() {
            Superwall.instance.register(event = "price_readout")
        }

        var testAndroid4Info = UITestInfo(
            4,
            "NOTE: Must use `Android Main screen` API key. Launch compose debug screen: " +
                    "Verify that paywall loads in Tab 0. Go to Tab 2 and press `Another Paywall` button. " +
                    "Verify that paywall does not load (only 1 paywall can be displayed at once).",
            testCaseType = TestCaseType.Android
        )

        suspend fun testAndroid4() {
            val intent = Intent(context, ComposeActivity::class.java)
            context.startActivity(intent)
        }
    }
}