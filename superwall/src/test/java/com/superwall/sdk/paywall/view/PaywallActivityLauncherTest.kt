package com.superwall.sdk.paywall.view

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PaywallActivityLauncherTest {
    @Test
    fun `activity not found is returned as a structured launch failure`() {
        val cause = ActivityNotFoundException("missing")
        val context = mockk<Context>()
        val intent = Intent()
        every { context.startActivity(intent) } throws cause

        val result = launchPaywallActivity(context, intent)

        val error = assertIs<PaywallActivityLaunchException>(result.exceptionOrNull())
        assertSame(cause, error.cause)
    }

    @Test
    fun `framework runtime exception is returned as a structured launch failure`() {
        val cause = NullPointerException("system_server WindowContainer failure")
        val context = mockk<Context>()
        val intent = Intent()
        every { context.startActivity(intent) } throws cause

        val result = launchPaywallActivity(context, intent)

        val error = assertIs<PaywallActivityLaunchException>(result.exceptionOrNull())
        assertSame(cause, error.cause)
    }

    @Test
    fun `non activity context launches in a new task`() {
        val context = mockk<Context>(relaxed = true)
        val intent = Intent()

        val result = launchPaywallActivity(context, intent)

        assertTrue(result.isSuccess)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `finishing activity is rejected before startActivity`() {
        val activity = mockk<Activity>(relaxed = true)
        val intent = Intent()
        every { activity.isFinishing } returns true

        val result = launchPaywallActivity(activity, intent)

        assertIs<PaywallActivityLaunchException>(result.exceptionOrNull())
        verify(exactly = 0) { activity.startActivity(any()) }
    }

    @Test
    fun `stopped lifecycle activity is not a valid presenter`() {
        val controller =
            Robolectric
                .buildActivity(TestLifecycleActivity::class.java)
                .create()
                .start()
                .resume()
        val activity = controller.get()
        assertTrue(activity.canPresentPaywall())

        controller.pause().stop()

        assertFalse(activity.canPresentPaywall())
    }

    class TestLifecycleActivity : ComponentActivity()
}
