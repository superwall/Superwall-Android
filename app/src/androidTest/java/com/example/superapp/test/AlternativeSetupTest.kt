package com.example.superapp.test

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.dropbox.dropshots.Dropshots
import com.dropbox.dropshots.ThresholdValidator
import com.example.superapp.utils.CustomComparator
import com.example.superapp.utils.awaitUntilShimmerDisappears
import com.example.superapp.utils.awaitUntilWebviewAppears
import com.example.superapp.utils.clickButtonWith
import com.example.superapp.utils.delayFor
import com.example.superapp.utils.goBack
import com.example.superapp.utils.paywallPresentsFor
import com.example.superapp.utils.screenshotFlow
import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.superapp.Keys
import com.superwall.superapp.test.UITestHandler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class AlternativeSetupTest {
    @get:Rule
    val dropshots =
        Dropshots(
            resultValidator = ThresholdValidator(0.01f),
            imageComparator = CustomComparator(),
        )

    @Test
    fun test_paywall_displays_on_session_start() =
        with(dropshots) {
            Superwall.configure(
                getInstrumentation().targetContext.applicationContext as Application,
                Keys.SESSION_START_API_KEY,
                options =
                    SuperwallOptions().apply {
                        paywalls.shouldPreload = false
                    },
            )
            paywallPresentsFor(UITestHandler.test50Info)
        }

    @Test
    fun test_paywall_displays_on_app_install() =
        with(dropshots) {
            Superwall.configure(
                getInstrumentation().targetContext.applicationContext as Application,
                Keys.APP_INSTALL_API_KEY,
                options =
                    SuperwallOptions().apply {
                        paywalls.shouldPreload = false
                    },
            )
            paywallPresentsFor(UITestHandler.test52Info)
        }

    @Test
    fun test_paywall_displays_on_app_launch() =
        with(dropshots) {
            Superwall.configure(
                getInstrumentation().targetContext.applicationContext as Application,
                Keys.APP_LAUNCH_API_KEY,
                options =
                    SuperwallOptions().apply {
                        paywalls.shouldPreload = false
                    },
            )
            paywallPresentsFor(UITestHandler.test53Info)
        }
/*
    @Test
    fun test_paywall_displays_on_deep_link() =
        with(dropshots) {
            Superwall.configure(
                getInstrumentation().targetContext.applicationContext as Application,
                Keys.DEEP_LINK_OPEN_API_KEY,
                options =
                    SuperwallOptions().apply {
                        paywalls.shouldPreload = false
                    },
            )
            paywallPresentsFor(UITestHandler.test57Info)
        }

 */

    @Test
    fun test_paywall_displays_on_decline() =
        with(dropshots) {
            Superwall.configure(
                getInstrumentation().targetContext.applicationContext as Application,
                Keys.PAYWALL_DECLINE_API_KEY,
                options =
                    SuperwallOptions().apply {
                        paywalls.shouldPreload = false
                    },
            )
            screenshotFlow(UITestHandler.test59Info) {
                step("1") {
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delayFor(300.milliseconds)
                }
                step("decline_for_survey") {
                    goBack()
                    delayFor(300.milliseconds)
                }
                step("decline_paywall") {
                    clickButtonWith("Too expensive")
                    awaitUntilShimmerDisappears()
                    awaitUntilWebviewAppears()
                    delayFor(300.milliseconds)
                }
            }
        }
}
