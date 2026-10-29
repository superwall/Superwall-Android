package com.superwall.sdk.notifications

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.core_data.CoreDataManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Private events go to Superwall's servers through the normal queue, but never reach the
 * delegate (customers forward it to their own analytics) or the on-device event store.
 */
class NotificationEventTrackingTest {
    private lateinit var superwall: Superwall
    private val delegateAdapter = mockk<SuperwallDelegateAdapter>(relaxed = true)
    private val coreDataManager = mockk<CoreDataManager>(relaxed = true)
    private val eventsQueue = mockk<EventsQueue>(relaxed = true)
    private val ioScope = IOScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        hasInitialized().value = true
        val storage = mockk<LocalStorage>(relaxed = true)
        every { storage.read(any<Storable<Any>>()) } returns null
        every { storage.coreDataManager } returns coreDataManager

        val container = mockk<DependencyContainer>(relaxed = true)
        every { container.storage } returns storage
        every { container.delegateAdapter } returns delegateAdapter
        every { container.eventsQueue } returns eventsQueue
        every { container.configManager.config } returns null
        every { container.makeIsSandbox() } returns false
        every { container.ioScope() } returns ioScope

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

    @After
    fun tearDown() {
        ioScope.cancel()
        hasInitialized().value = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun hasInitialized(): MutableStateFlow<Boolean> =
        Superwall::class.java
            .getDeclaredField("_hasInitialized")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<Boolean>

    private val token =
        PrivateSuperwallEvents.NotificationToken(
            NotificationTokenPayload(
                address = "fGx7Q2kTRaSc:APA91bH",
                permissionStatus = NotificationPermissionStatus.AUTHORIZED,
                enabled = true,
                appVersion = "2.3.4",
                sdkVersion = "2.9.0",
            ),
        )

    private val open = PrivateSuperwallEvents.NotificationOpen("send_123", "cp_456")

    @Test
    fun `private events are queued for the server but not given to the delegate or saved`() =
        runTest {
            listOf<Trackable>(token, open).forEach { event ->
                val result = superwall.track(event).getOrThrow()
                assertEquals(true, result.parameters.audienceFilterParams["\$is_standard_event"])
            }

            verify(exactly = 2) { eventsQueue.enqueue(any(), any()) }
            verify(exactly = 0) { delegateAdapter.handleSuperwallEvent(any()) }
            verify(exactly = 0) { coreDataManager.saveEventData(any(), any()) }
        }

    @Test
    fun `a Superwall event still reaches the delegate and is saved`() =
        runTest {
            superwall.track(InternalSuperwallEvent.Reset).getOrThrow()

            verify(exactly = 1) { eventsQueue.enqueue(any(), any()) }
            verify(exactly = 1) { delegateAdapter.handleSuperwallEvent(any()) }
            verify(exactly = 1) { coreDataManager.saveEventData(any(), any()) }
        }

    @Test
    fun `private events can't implicitly trigger a paywall`() {
        assertEquals(false, token.canImplicitlyTriggerPaywall)
        assertEquals(false, open.canImplicitlyTriggerPaywall)
    }
}
