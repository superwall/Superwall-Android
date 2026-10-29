package com.superwall.sdk.notifications

import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.notifications.NotificationTokenLogic.Identity
import com.superwall.sdk.storage.SearchPathDirectory
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredNotificationTokenLastSent
import com.superwall.sdk.storage.StoredNotificationTokenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors iOS's `NotificationTokenManagerTests`: dedup by fingerprint and a 24h heartbeat,
 * a device-scoped opt-out, flag-off caching then a single flush, and tracking gating.
 */
class NotificationTokenManagerTest {
    private class InMemoryStorage : Storage {
        val values = mutableMapOf<String, Any?>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> read(storable: Storable<T>): T? = values[storable.key] as T?

        override fun <T : Any> write(
            storable: Storable<T>,
            data: T,
        ) {
            values[storable.key] = data
        }

        override fun clean() {}
    }

    private val storage = InMemoryStorage()
    private val tracked = mutableListOf<Trackable>()
    private var permission = NotificationPermissionStatus.AUTHORIZED
    private var permissionChecks = 0
    private var trackingBehavior = EventTrackingBehavior.ALL
    private var identity = Identity(aliasId = "\$SuperwallAlias:alias-1", appUserId = null)
    private var nowMs = 1_000_000L
    private val configState = MutableStateFlow<ConfigState>(configWithFlag(true))

    private val sentTokens: List<NotificationTokenPayload>
        get() = tracked.filterIsInstance<PrivateSuperwallEvents.NotificationToken>().map { it.payload }

    private fun configWithFlag(enabled: Boolean) =
        ConfigState.Retrieved(
            Config.stub().copy(rawFeatureFlags = listOf(RawFeatureFlag("user_notifications", enabled))),
        )

    private fun manager(scope: CoroutineScope) =
        NotificationTokenManager(
            storage = storage,
            tracker = { tracked += it },
            permissionStatus = {
                permissionChecks += 1
                permission
            },
            eventTrackingBehavior = { trackingBehavior },
            configState = configState,
            identity = { identity },
            appVersion = { "2.3.4" },
            sdkVersion = "2.9.0",
            scope = scope,
            now = { nowMs },
        )

    private fun TestScope.managerWithToken(token: String = TOKEN): NotificationTokenManager {
        val manager = manager(backgroundScope)
        manager.setPushToken(token)
        runCurrent()
        return manager
    }

    @Test
    fun `a new token is sent with the contract parameters`() =
        runTest {
            managerWithToken()

            val payload = sentTokens.single()
            assertEquals(
                mapOf(
                    "channel" to "fcm",
                    "address" to TOKEN,
                    "permissionStatus" to "AUTHORIZED",
                    "enabled" to true,
                    "appVersion" to "2.3.4",
                    "sdkVersion" to "2.9.0",
                ),
                payload.parameters,
            )
            assertEquals(nowMs, storage.read(StoredNotificationTokenLastSent)?.sentAt)
        }

    @Test
    fun `an unchanged state isn't re-sent within 24 hours`() =
        runTest {
            val manager = managerWithToken()

            nowMs += NotificationTokenLogic.HEARTBEAT_INTERVAL_MS - 1
            manager.setPushToken(TOKEN)
            manager.refresh()
            runCurrent()

            assertEquals(1, sentTokens.size)
        }

    @Test
    fun `an unchanged state is re-sent after 24 hours`() =
        runTest {
            val manager = managerWithToken()

            nowMs += NotificationTokenLogic.HEARTBEAT_INTERVAL_MS
            manager.refresh()
            runCurrent()

            assertEquals(2, sentTokens.size)
        }

    @Test
    fun `a new token is sent straight away`() =
        runTest {
            val manager = managerWithToken()

            manager.setPushToken("other-token")
            runCurrent()

            assertEquals(listOf(TOKEN, "other-token"), sentTokens.map { it.address })
        }

    @Test
    fun `a permission change is sent when the app comes to the foreground`() =
        runTest {
            val foregrounds = MutableSharedFlow<Unit>()
            val manager = manager(backgroundScope)
            manager.listen(identityChanges = emptyFlow(), appForegrounded = foregrounds)
            manager.setPushToken(TOKEN)
            runCurrent()

            foregrounds.emit(Unit)
            runCurrent()
            assertEquals(1, sentTokens.size)

            permission = NotificationPermissionStatus.DENIED
            foregrounds.emit(Unit)
            runCurrent()

            assertEquals(
                listOf(NotificationPermissionStatus.AUTHORIZED, NotificationPermissionStatus.DENIED),
                sentTokens.map { it.permissionStatus },
            )
            assertEquals(NotificationPermissionStatus.DENIED, storage.read(StoredNotificationTokenState)?.permissionStatus)
        }

    @Test
    fun `identify re-sends the state for the new user`() =
        runTest {
            val identities = MutableStateFlow(identity)
            val manager = manager(backgroundScope)
            manager.listen(identityChanges = identities, appForegrounded = emptyFlow())
            manager.setPushToken(TOKEN)
            runCurrent()
            assertEquals(1, sentTokens.size)

            identity = identity.copy(appUserId = "user_123")
            identities.value = identity
            runCurrent()

            assertEquals(2, sentTokens.size)
        }

    @Test
    fun `reset re-sends the state for the new alias`() =
        runTest {
            val manager = managerWithToken()

            identity = Identity(aliasId = "\$SuperwallAlias:alias-2", appUserId = null)
            manager.refresh()
            runCurrent()

            assertEquals(2, sentTokens.size)
        }

    @Test
    fun `opting out sends the token disabled, and opting back in re-enables it`() =
        runTest {
            val manager = managerWithToken()

            manager.optOut()
            manager.optIn()
            runCurrent()

            assertEquals(listOf(true, false, true), sentTokens.map { it.enabled })
        }

    @Test
    fun `the opt-out survives a relaunch and an identity change`() =
        runTest {
            val manager = managerWithToken()
            manager.optOut()
            runCurrent()

            // A relaunch: a new manager over the same storage.
            identity = Identity(aliasId = "\$SuperwallAlias:alias-2", appUserId = "user_123")
            val relaunched = manager(backgroundScope)
            relaunched.setPushToken("refreshed-token")
            runCurrent()

            val last = sentTokens.last()
            assertEquals("refreshed-token", last.address)
            assertFalse(last.enabled)
        }

    @Test
    fun `the state lives in app-specific storage, which reset keeps`() {
        assertEquals(SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, StoredNotificationTokenState.directory)
        assertEquals(SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, StoredNotificationTokenLastSent.directory)
    }

    @Test
    fun `with the flag off nothing is sent, and the cached state is flushed once when it turns on`() =
        runTest {
            configState.value = configWithFlag(false)
            val manager = manager(backgroundScope)
            manager.listen(identityChanges = emptyFlow(), appForegrounded = emptyFlow())
            manager.setPushToken(TOKEN)
            manager.optOut()
            runCurrent()

            assertTrue(tracked.isEmpty())
            assertEquals(
                NotificationTokenState(TOKEN, false, NotificationPermissionStatus.AUTHORIZED),
                storage.read(StoredNotificationTokenState),
            )
            assertNull(storage.read(StoredNotificationTokenLastSent))

            configState.value = configWithFlag(true)
            runCurrent()
            // A config refresh with the flag still on doesn't send it again.
            configState.value = ConfigState.Retrieving
            configState.value = configWithFlag(true)
            runCurrent()

            val payload = sentTokens.single()
            assertEquals(TOKEN, payload.address)
            assertFalse(payload.enabled)
        }

    @Test
    fun `nothing is sent or marked sent while tracking is off, and it goes once tracking is back`() =
        runTest {
            trackingBehavior = EventTrackingBehavior.NONE
            val manager = managerWithToken()

            assertTrue(tracked.isEmpty())
            assertNull(storage.read(StoredNotificationTokenLastSent))

            trackingBehavior = EventTrackingBehavior.ALL
            manager.refresh()
            manager.refresh()
            runCurrent()

            assertEquals(1, sentTokens.size)
        }

    @Test
    fun `without a token nothing is sent and the permission isn't checked`() =
        runTest {
            val manager = manager(backgroundScope)
            manager.refresh()
            manager.optOut()
            runCurrent()

            assertTrue(tracked.isEmpty())
            assertEquals(0, permissionChecks)
            assertFalse(storage.read(StoredNotificationTokenState)!!.enabled)
        }

    @Test
    fun `a Superwall notification open is tracked and other notifications are ignored`() =
        runTest {
            val manager = manager(backgroundScope)

            manager.trackOpen(mapOf("sw" to """{"sendId":"send_123","cpId":"cp_456"}""", "deep_link" to "app://x"))
            manager.trackOpen(mapOf("title" to "Not ours"))
            runCurrent()

            assertEquals(
                PrivateSuperwallEvents.NotificationOpen(sendId = "send_123", contactPointId = "cp_456"),
                tracked.single(),
            )
        }

    private fun NotificationTokenManager.optOut() = setEnabled(false)

    private fun NotificationTokenManager.optIn() = setEnabled(true)

    private companion object {
        // FCM tokens are case-sensitive, so the fixture keeps uppercase letters.
        const val TOKEN = "fGx7Q2kTRaSc:APA91bH-Ur1Xy_ZkLmN0pQ"
    }
}
