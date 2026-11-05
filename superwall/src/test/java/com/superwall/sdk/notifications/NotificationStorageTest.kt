package com.superwall.sdk.notifications

import android.content.Context
import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.dependencies.attachMockGraph
import com.superwall.sdk.logger.LogQueue
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.notifications.NotificationTokenLogic.Identity
import com.superwall.sdk.storage.Cache
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.StoredNotificationTokenLastSent
import com.superwall.sdk.storage.StoredNotificationTokenState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The token and opt-out live on disk in the real [LocalStorage], so they survive
 * [LocalStorage.reset] and are never quoted into a log.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationStorageTest {
    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun localStorage(track: suspend (Trackable) -> Unit = {}) =
        LocalStorage(
            context = context,
            json = json,
            _apiKey = "test_key",
            factory = mockk(relaxed = true),
            ioScope = IOScope(UnconfinedTestDispatcher()),
            cache = Cache(context, ioQueue = UnconfinedTestDispatcher(), json = json),
            coreDataManager = mockk(relaxed = true),
            track = track,
        )

    @Test
    fun `reset keeps the token and the opt-out, and the token still goes out disabled`() =
        runTest {
            // Reset tracks first_seen on a background thread, so wait until it has.
            val trackedFirstSeen = CountDownLatch(1)
            val storage = localStorage(track = { trackedFirstSeen.countDown() })
            val tracked = mutableListOf<Trackable>()
            var identity = Identity(aliasId = "\$SuperwallAlias:alias-1", appUserId = null)
            val manager =
                NotificationTokenManager(
                    storage = storage,
                    tracker = { tracked += it },
                    permissionStatus = { NotificationPermissionStatus.AUTHORIZED },
                    trackingBehavior = MutableStateFlow(EventTrackingBehavior.ALL),
                    configState =
                        MutableStateFlow(
                            ConfigState.Retrieved(
                                Config.stub().copy(rawFeatureFlags = listOf(RawFeatureFlag("user_notifications", true))),
                            ),
                        ),
                    identity = { identity },
                    appVersion = { "2.3.4" },
                    sdkVersion = "2.9.0",
                    scope = backgroundScope,
                )
            manager.setPushToken(TOKEN)
            manager.setEnabled(false)
            runCurrent()
            val lastSent = storage.read(StoredNotificationTokenLastSent)

            storage.reset()
            assertTrue(trackedFirstSeen.await(5, TimeUnit.SECONDS))

            assertEquals(
                NotificationTokenState(TOKEN, false, NotificationPermissionStatus.AUTHORIZED),
                storage.read(StoredNotificationTokenState),
            )
            assertEquals(lastSent, storage.read(StoredNotificationTokenLastSent))
            // A relaunch reads it back from disk, not from the memory cache that reset cleared.
            assertEquals(
                NotificationTokenState(TOKEN, false, NotificationPermissionStatus.AUTHORIZED),
                localStorage().read(StoredNotificationTokenState),
            )

            // Reset gives the device a new alias, which the token is re-sent for, still opted out.
            val sentBeforeReset = tracked.size
            identity = Identity(aliasId = "\$SuperwallAlias:alias-2", appUserId = null)
            manager.refresh()
            runCurrent()
            assertEquals(sentBeforeReset + 1, tracked.size)
            val last = (tracked.last() as PrivateSuperwallEvents.NotificationToken).payload
            assertEquals(TOKEN, last.address)
            assertFalse(last.enabled)
        }

    @Test
    fun `a stored state that can't be decoded is logged by key, without its contents`() {
        val logs = mutableListOf<String>()
        val adapter = SuperwallDelegateAdapter()
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun handleLog(
                    level: String,
                    scope: String,
                    message: String?,
                    info: Map<String, Any>?,
                    error: Throwable?,
                ) {
                    logs += "$message $info $error"
                }
            }
        val container = attachMockGraph()
        every { container.delegateAdapter } returns adapter
        LogQueue.synchronous = true

        try {
            // Valid JSON for another shape, so the decoder quotes it in its exception.
            StoredNotificationTokenState.file(context).writeText("""{"address":["$TOKEN"]}""")

            assertNull(Cache(context, ioQueue = UnconfinedTestDispatcher(), json = json).read(StoredNotificationTokenState))

            val log = logs.single { it.contains("Unable to read key") }
            assertTrue(log, log.contains(StoredNotificationTokenState.key))
            logs.forEach { assertFalse(it, it.contains(TOKEN)) }
        } finally {
            StoredNotificationTokenState.file(context).delete()
            LogQueue.synchronous = false
            SuperwallGraph.detach(container)
            SuperwallDelegateAdapter()
        }
    }

    private companion object {
        const val TOKEN = "fGx7Q2kTRaSc:APA91bH-Ur1Xy_ZkLmN0pQ"
    }
}
