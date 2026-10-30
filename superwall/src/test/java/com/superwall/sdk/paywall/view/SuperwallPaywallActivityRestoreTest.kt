package com.superwall.sdk.paywall.view

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.paywall.manager.PaywallViewRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the process dies with a paywall on top, Android recreates the activity in a new
 * process. The paywall (and the handler and feature block that went with it) is gone, so the
 * activity must close rather than sit there blank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SuperwallPaywallActivityRestoreTest {
    @Before
    fun setUp() {
        mockkObject(Superwall.Companion)
    }

    @After
    fun tearDown() {
        unmockkObject(Superwall.Companion)
    }

    private fun recreatedActivity(): SuperwallPaywallActivity {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), SuperwallPaywallActivity::class.java)
                .putExtra("viewKey", "paywall-key")
        val savedState = Bundle().apply { putString("viewKey", "paywall-key") }
        val controller = Robolectric.buildActivity(SuperwallPaywallActivity::class.java, intent)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        return controller.create(savedState).get()
    }

    @Test
    fun `finishes when Superwall is not configured yet after process death`() {
        Given("the app configures Superwall after Application.onCreate, so it isn't configured yet") {
            every { Superwall.instance } throws IllegalStateException("Superwall has not been configured")

            When("the system recreates the paywall activity") {
                val activity = recreatedActivity()

                Then("the activity finishes instead of showing a blank screen") {
                    assertTrue(activity.isFinishing)
                }
            }
        }
    }

    @Test
    fun `finishes when the paywall view is gone after process death`() {
        Given("Superwall is configured but its view registry is empty") {
            val registry = mockk<PaywallViewRegistry>(relaxed = true) { every { retrieveView(any()) } returns null }
            every { Superwall.instance } returns
                mockk(relaxed = true) {
                    every { dependencyContainer.makeViewRegistry() } returns registry
                }

            When("the system recreates the paywall activity") {
                val activity = recreatedActivity()

                Then("the activity finishes") {
                    assertTrue(activity.isFinishing)
                }
            }
        }
    }
}
