package com.superwall.sdk.notifications

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.options.AdConsent
import com.superwall.sdk.config.options.AdConsentPublisher
import com.superwall.sdk.config.options.AdConsentSource
import com.superwall.sdk.config.options.AdConsentState
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.config.options.ReportedAdConsent
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.network.Network
import com.superwall.sdk.notifications.NotificationTokenLogic.Identity
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredNotificationTokenLastSent
import com.superwall.sdk.storage.StoredNotificationTokenState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turning tracking back on goes through [Superwall.eventTrackingBehavior], which updates the
 * options straight away but the events queue later, through the ad consent actor. A token
 * sent in between is dropped by the queue, so it must not be marked as sent, or it would wait
 * a day for the heartbeat. Everything here is real except the container around it and the
 * network.
 */
class NotificationTrackingBehaviorTest {
    private val options = SuperwallOptions().apply { eventTrackingBehavior = EventTrackingBehavior.NONE }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queueIoScope = IOScope(Dispatchers.Default)
    private val sentRequests = CopyOnWriteArrayList<EventsRequest>()

    /** The queue's behavior at the moment the token was marked as sent. */
    @Volatile
    private var queueBehaviorWhenMarkedSent: EventTrackingBehavior? = null

    private lateinit var superwall: Superwall
    private lateinit var eventsQueue: EventsQueue
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        hasInitialized().value = true

        val configManager = mockk<ConfigManager>(relaxed = true)
        every { configManager.options } returns options
        every { configManager.config } returns null

        val network = mockk<Network>(relaxed = true)
        coEvery { network.sendEvents(any()) } answers {
            sentRequests += firstArg<EventsRequest>()
            Either.Success(Unit)
        }
        eventsQueue =
            EventsQueue(
                context = mockk<Context>(relaxed = true),
                network = network,
                ioScope = queueIoScope,
                mainScope = MainScope(Dispatchers.Unconfined),
                configManager = configManager,
            )

        storage =
            object : Storage {
                val values = ConcurrentHashMap<String, Any>()

                @Suppress("UNCHECKED_CAST")
                override fun <T> read(storable: Storable<T>): T? = values[storable.key] as T?

                override fun <T : Any> write(
                    storable: Storable<T>,
                    data: T,
                ) {
                    if (storable == StoredNotificationTokenLastSent) {
                        queueBehaviorWhenMarkedSent = eventsQueue.trackingBehavior.value
                    }
                    values[storable.key] = data
                }

                override fun clean() {}
            }

        val trackingStorage = mockk<LocalStorage>(relaxed = true)
        every { trackingStorage.read(any<Storable<Any>>()) } returns null

        val container = mockk<DependencyContainer>(relaxed = true)
        every { container.configManager } returns configManager
        every { container.storage } returns trackingStorage
        every { container.eventsQueue } returns eventsQueue
        every { container.makeIsSandbox() } returns false
        every { container.ioScope() } returns IOScope(Dispatchers.Unconfined)
        every { container.mainScope() } returns MainScope(Dispatchers.Unconfined)
        every { container.paywallManager.currentView } returns null

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

        every { container.adConsentPublisher } returns
            AdConsentPublisher(
                scope = scope,
                tracker = {},
                makeDeviceAttributes = { hashMapOf() },
                makeConfigAttributes = { mockk(relaxed = true) },
                currentAdConsent = { ReportedAdConsent(AdConsent.Custom(), AdConsentSource.DEFAULT) },
                eventTrackingBehavior = { options.eventTrackingBehavior },
                updateEventsQueue = { eventsQueue.setTrackingBehavior(it) },
                actor = SequentialActor(AdConsentState(), scope),
            )

        val manager =
            NotificationTokenManager(
                storage = storage,
                tracker = { superwall.track(it) },
                permissionStatus = { NotificationPermissionStatus.AUTHORIZED },
                trackingBehavior = eventsQueue.trackingBehavior,
                configState =
                    MutableStateFlow(
                        ConfigState.Retrieved(
                            Config.stub().copy(rawFeatureFlags = listOf(RawFeatureFlag("user_notifications", true))),
                        ),
                    ),
                identity = { Identity(aliasId = "\$SuperwallAlias:alias-1", appUserId = null) },
                appVersion = { "2.3.4" },
                sdkVersion = "2.9.0",
                scope = scope,
            )
        manager.listen(identityChanges = emptyFlow(), appForegrounded = emptyFlow())
        every { container.notificationTokenManager } returns manager
    }

    @After
    fun tearDown() {
        scope.cancel()
        queueIoScope.cancel()
        hasInitialized().value = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun hasInitialized(): MutableStateFlow<Boolean> =
        Superwall::class.java
            .getDeclaredField("_hasInitialized")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<Boolean>

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(10)
        }
    }

    private fun sentTokenAddresses(): List<Any?> =
        sentRequests
            .flatMap { it.events }
            .filter { it.name == PrivateSuperwallEvents.NotificationToken.NAME }
            .map { it.parameters["address"] }

    @Test
    fun `turning tracking back on sends the token that was kept while it was off`() {
        superwall.setPushToken(TOKEN)
        awaitUntil { storage.read(StoredNotificationTokenState)?.address == TOKEN }
        assertNull(storage.read(StoredNotificationTokenLastSent))

        superwall.eventTrackingBehavior = EventTrackingBehavior.ALL

        awaitUntil { storage.read(StoredNotificationTokenLastSent) != null }
        assertEquals(EventTrackingBehavior.ALL, queueBehaviorWhenMarkedSent)

        // The event the token was marked sent for is in the queue, and goes out with it.
        runBlocking { eventsQueue.flushInternal() }
        awaitUntil { sentTokenAddresses().isNotEmpty() }
        assertEquals(listOf(TOKEN), sentTokenAddresses())
        assertTrue(sentRequests.isNotEmpty())
    }

    private companion object {
        const val TOKEN = "fGx7Q2kTRaSc:APA91bH-Ur1Xy_ZkLmN0pQ"
    }
}
