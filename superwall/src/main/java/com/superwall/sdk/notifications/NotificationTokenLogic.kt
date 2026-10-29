package com.superwall.sdk.notifications

import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.misc.sha256Hex
import com.superwall.sdk.permissions.PermissionStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The notification permission as reported to Superwall. */
internal enum class NotificationPermissionStatus {
    AUTHORIZED,
    DENIED,
    ;

    companion object {
        fun from(status: PermissionStatus): NotificationPermissionStatus = if (status == PermissionStatus.GRANTED) AUTHORIZED else DENIED
    }
}

/**
 * What the SDK knows about the device's push registration. Persisted so it survives
 * launches, and so it can be sent as soon as notifications are turned on for the app.
 */
@Serializable
internal data class NotificationTokenState(
    /** The FCM registration token, once the app has passed it in. */
    val address: String? = null,
    /** `false` once the app opts the device out of Superwall notifications. */
    val enabled: Boolean = true,
    val permissionStatus: NotificationPermissionStatus? = null,
)

/** The last `notification_token` event that was sent. */
@Serializable
internal data class NotificationTokenLastSent(
    /** A hash of the event's parameters and the identity it was sent for. */
    val fingerprint: String,
    /** Epoch milliseconds. */
    val sentAt: Long,
)

/** The contents of a `notification_token` event. */
internal data class NotificationTokenPayload(
    /** The FCM registration token, exactly as the app passed it in. */
    val address: String,
    val permissionStatus: NotificationPermissionStatus,
    val enabled: Boolean,
    val appVersion: String,
    val sdkVersion: String,
) {
    /** The event's parameters, keyed exactly as the server reads them. */
    val parameters: Map<String, Any>
        get() =
            mapOf(
                "channel" to CHANNEL,
                "address" to address,
                "permissionStatus" to permissionStatus.name,
                "enabled" to enabled,
                "appVersion" to appVersion,
                "sdkVersion" to sdkVersion,
            )

    companion object {
        const val CHANNEL = "fcm"
    }
}

internal object NotificationTokenLogic {
    /** How long an unchanged state goes before it's sent again. */
    const val HEARTBEAT_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** The FCM data key that marks a notification as sent by Superwall. */
    const val SUPERWALL_DATA_KEY = "sw"

    data class Identity(
        val aliasId: String,
        val appUserId: String?,
    )

    fun shouldSend(
        fingerprint: String,
        lastSent: NotificationTokenLastSent?,
        now: Long,
    ): Boolean {
        if (lastSent == null || lastSent.fingerprint != fingerprint) {
            return true
        }
        return now - lastSent.sentAt >= HEARTBEAT_INTERVAL_MS
    }

    /**
     * A hash of everything sent and who it was sent for. It's stored instead of the
     * identity and token themselves.
     */
    fun fingerprint(
        payload: NotificationTokenPayload,
        identity: Identity,
    ): String {
        val fields =
            (payload.parameters + mapOf("aliasId" to identity.aliasId, "appUserId" to identity.appUserId.orEmpty()))
                .toSortedMap()
                .mapValues { (_, value) ->
                    when (value) {
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
                }
        return JsonObject(fields).toString().sha256Hex().orEmpty()
    }

    /**
     * The Superwall send a notification belongs to, read from its FCM data. FCM data values
     * are strings, so `sw` holds a JSON string: `{"sendId":"…","cpId":"…"}`.
     *
     * Returns `null` for notifications Superwall didn't send.
     */
    fun notificationOpen(data: Map<String, String>): PrivateSuperwallEvents.NotificationOpen? {
        val raw = data[SUPERWALL_DATA_KEY] ?: return null
        val superwall =
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return null
        val sendId = superwall.string("sendId")?.takeIf { it.isNotEmpty() } ?: return null
        return PrivateSuperwallEvents.NotificationOpen(
            sendId = sendId,
            contactPointId = superwall.string("cpId")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
