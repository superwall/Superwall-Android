package com.superwall.sdk.notifications

import com.superwall.sdk.analytics.internal.TrackingLogic
import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.notifications.NotificationTokenLogic.Identity
import com.superwall.sdk.permissions.PermissionStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class NotificationTokenLogicTest {
    private val payload =
        NotificationTokenPayload(
            address = TOKEN,
            permissionStatus = NotificationPermissionStatus.AUTHORIZED,
            enabled = true,
            appVersion = "2.3.4",
            sdkVersion = "2.9.0",
        )

    // region notification_open parsing

    @Test
    fun `an sw JSON string with sendId and cpId is a Superwall open`() {
        val event = NotificationTokenLogic.notificationOpen(mapOf("sw" to """{"sendId":"send_123","cpId":"cp_456"}"""))

        assertEquals(PrivateSuperwallEvents.NotificationOpen("send_123", "cp_456"), event)
        assertEquals(mapOf("sendId" to "send_123", "contactPointId" to "cp_456"), event!!.audienceFilterParams)
    }

    @Test
    fun `a missing cpId leaves contactPointId out`() {
        val event = NotificationTokenLogic.notificationOpen(mapOf("sw" to """{"sendId":"send_123"}"""))

        assertEquals(PrivateSuperwallEvents.NotificationOpen("send_123", null), event)
        assertEquals(mapOf("sendId" to "send_123"), event!!.audienceFilterParams)
    }

    @Test
    fun `notifications without a valid sw are ignored`() {
        listOf(
            emptyMap(),
            mapOf("deep_link" to "app://paywall"),
            mapOf("sw" to "not json"),
            mapOf("sw" to "{"),
            mapOf("sw" to """["send_123"]"""),
            mapOf("sw" to "\"send_123\""),
            mapOf("sw" to """{"cpId":"cp_456"}"""),
            mapOf("sw" to """{"sendId":""}"""),
            mapOf("sw" to """{"sendId":123}"""),
            mapOf("sw" to """{"sendId":null}"""),
        ).forEach { data ->
            assertNull("$data", NotificationTokenLogic.notificationOpen(data))
        }
    }

    // endregion

    // region Dedup

    @Test
    fun `the fingerprint changes with the state and the identity`() {
        val identity = Identity("alias-1", null)
        val fingerprint = NotificationTokenLogic.fingerprint(payload, identity)

        assertEquals(fingerprint, NotificationTokenLogic.fingerprint(payload.copy(), identity.copy()))
        assertEquals(64, fingerprint.length)
        assertFalse(fingerprint.contains(TOKEN))
        listOf(
            NotificationTokenLogic.fingerprint(payload.copy(address = TOKEN.lowercase()), identity),
            NotificationTokenLogic.fingerprint(payload.copy(enabled = false), identity),
            NotificationTokenLogic.fingerprint(payload.copy(permissionStatus = NotificationPermissionStatus.DENIED), identity),
            NotificationTokenLogic.fingerprint(payload, identity.copy(aliasId = "alias-2")),
            NotificationTokenLogic.fingerprint(payload, identity.copy(appUserId = "user_123")),
        ).forEach { assertNotEquals(fingerprint, it) }
    }

    @Test
    fun `shouldSend dedups within the heartbeat interval`() {
        val lastSent = NotificationTokenLastSent("abc", sentAt = 1_000L)

        assertTrue(NotificationTokenLogic.shouldSend("abc", null, 1_000L))
        assertTrue(NotificationTokenLogic.shouldSend("def", lastSent, 1_001L))
        assertFalse(NotificationTokenLogic.shouldSend("abc", lastSent, 1_000L + NotificationTokenLogic.HEARTBEAT_INTERVAL_MS - 1))
        assertTrue(NotificationTokenLogic.shouldSend("abc", lastSent, 1_000L + NotificationTokenLogic.HEARTBEAT_INTERVAL_MS))
    }

    @Test
    fun `permission maps granted to AUTHORIZED and anything else to DENIED`() {
        assertEquals(NotificationPermissionStatus.AUTHORIZED, NotificationPermissionStatus.from(PermissionStatus.GRANTED))
        assertEquals(NotificationPermissionStatus.DENIED, NotificationPermissionStatus.from(PermissionStatus.DENIED))
        assertEquals(NotificationPermissionStatus.DENIED, NotificationPermissionStatus.from(PermissionStatus.UNSUPPORTED))
    }

    @Test
    fun `the user_notifications toggle sets the feature flag, off by default`() {
        assertFalse(Config.stub().featureFlags.userNotifications)
        assertTrue(
            Config
                .stub()
                .copy(rawFeatureFlags = listOf(RawFeatureFlag("user_notifications", true)))
                .featureFlags.userNotifications,
        )
    }

    // endregion

    // region Wire contract

    /** Encodes [event] the way the events queue sends it and returns its `parameters`. */
    private suspend fun wireParameters(event: Trackable): JsonObject {
        val parameters = TrackingLogic.processParameters(event, appSessionId = "session-1")
        val request =
            EventsRequest(
                listOf(EventData(name = event.rawName, parameters = parameters.audienceFilterParams, createdAt = Date())),
            )
        val body = Json.parseToJsonElement(JsonFactory.JSON.encodeToString(request)).jsonObject
        val encoded = body["events"]!!.jsonArray.single().jsonObject
        assertEquals(event.rawName, encoded["event_name"]!!.jsonPrimitive.content)
        return encoded["parameters"]!!.jsonObject
    }

    @Test
    fun `notification_token encodes the exact contract parameters`() =
        runTest {
            val parameters = wireParameters(PrivateSuperwallEvents.NotificationToken(payload))

            assertEquals(
                JsonObject(
                    mapOf(
                        "\$is_standard_event" to JsonPrimitive(true),
                        "\$event_name" to JsonPrimitive("notification_token"),
                        "event_name" to JsonPrimitive("notification_token"),
                        "\$app_session_id" to JsonPrimitive("session-1"),
                        "channel" to JsonPrimitive("fcm"),
                        "address" to JsonPrimitive(TOKEN),
                        "permissionStatus" to JsonPrimitive("AUTHORIZED"),
                        "enabled" to JsonPrimitive(true),
                        "appVersion" to JsonPrimitive("2.3.4"),
                        "sdkVersion" to JsonPrimitive("2.9.0"),
                    ),
                ),
                parameters,
            )
        }

    @Test
    fun `an opted-out notification_token encodes enabled false`() =
        runTest {
            val parameters = wireParameters(PrivateSuperwallEvents.NotificationToken(payload.copy(enabled = false)))

            assertEquals(JsonPrimitive(false), parameters["enabled"])
        }

    @Test
    fun `notification_open encodes sendId and contactPointId`() =
        runTest {
            val parameters = wireParameters(PrivateSuperwallEvents.NotificationOpen("send_123", "cp_456"))

            assertEquals(
                JsonObject(
                    mapOf(
                        "\$is_standard_event" to JsonPrimitive(true),
                        "\$event_name" to JsonPrimitive("notification_open"),
                        "event_name" to JsonPrimitive("notification_open"),
                        "\$app_session_id" to JsonPrimitive("session-1"),
                        "sendId" to JsonPrimitive("send_123"),
                        "contactPointId" to JsonPrimitive("cp_456"),
                    ),
                ),
                parameters,
            )
        }

    // endregion

    private companion object {
        // FCM tokens are case-sensitive, so the fixture keeps uppercase letters.
        const val TOKEN = "fGx7Q2kTRaSc:APA91bH-Ur1Xy_ZkLmN0pQ"
    }
}
