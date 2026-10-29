package com.superwall.sdk.notifications

import android.content.Context
import android.content.Intent
import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.DependencyContainer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FCM puts a displayed notification's data keys in its tap intent's extras, so the `sw`
 * extra is what [Superwall.handleNotificationOpen] reads from an intent.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationOpenIntentTest {
    private val manager = mockk<NotificationTokenManager>(relaxed = true)
    private lateinit var superwall: Superwall

    @Before
    fun setUp() {
        val container = mockk<DependencyContainer>(relaxed = true)
        every { container.notificationTokenManager } returns manager
        superwall =
            Superwall(
                context = mockk<Context>(relaxed = true),
                apiKey = "test",
                purchaseController = null,
                options = null,
                activityProvider = null,
                completion = null,
            )
        Superwall::class.java.getDeclaredField("_dependencyContainer").apply {
            isAccessible = true
            set(superwall, container)
        }
    }

    @Test
    fun `the sw extra of a tapped notification is tracked as an open`() {
        val intent =
            Intent()
                .putExtra("sw", SW_DATA)
                .putExtra("deep_link", "app://x")

        superwall.handleNotificationOpen(intent)

        verify(exactly = 1) { manager.trackOpen(mapOf("sw" to SW_DATA)) }
    }

    @Test
    fun `an intent without the sw extra is ignored`() {
        superwall.handleNotificationOpen(Intent().putExtra("title", "Not ours"))

        verify(exactly = 0) { manager.trackOpen(any()) }
    }

    @Test
    fun `an sw extra that isn't a string is ignored`() {
        superwall.handleNotificationOpen(Intent().putExtra("sw", 42))

        verify(exactly = 0) { manager.trackOpen(any()) }
    }

    private companion object {
        const val SW_DATA = """{"sendId":"send_123","cpId":"cp_456"}"""
    }
}
