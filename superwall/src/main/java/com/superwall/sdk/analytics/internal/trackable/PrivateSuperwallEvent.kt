package com.superwall.sdk.analytics.internal.trackable

import com.superwall.sdk.notifications.NotificationTokenPayload

/**
 * An event the SDK sends to Superwall's servers and nowhere else.
 *
 * Unlike a [TrackableSuperwallEvent] it isn't a `SuperwallEvent`, so it never reaches
 * `SuperwallDelegate.handleSuperwallEvent`, can't trigger a paywall and isn't stored
 * on-device for audience filters. Customers often forward every Superwall event to their
 * own analytics, so anything sensitive, such as a push token, must only travel in one of these.
 *
 * Its [audienceFilterParams] are sent exactly as they're keyed: they aren't given a `$`
 * prefix, and the events encoder leaves map keys alone.
 */
internal interface PrivateSuperwallEvent : Trackable {
    override val canImplicitlyTriggerPaywall: Boolean
        get() = false

    override suspend fun getSuperwallParameters(): Map<String, Any> = emptyMap()
}

internal sealed class PrivateSuperwallEvents : PrivateSuperwallEvent {
    /**
     * Reports the device's push token and notification permission, so Superwall can send
     * the device notifications. The collector routes it by name to its own topic, keeping
     * the token out of the analytics tables.
     */
    data class NotificationToken(
        val payload: NotificationTokenPayload,
    ) : PrivateSuperwallEvents() {
        override val rawName: String
            get() = NAME

        override val audienceFilterParams: Map<String, Any>
            get() = payload.parameters

        companion object {
            const val NAME = "notification_token"
        }
    }

    /** Tracked when the user opens a notification that Superwall sent. */
    data class NotificationOpen(
        val sendId: String,
        val contactPointId: String?,
    ) : PrivateSuperwallEvents() {
        override val rawName: String
            get() = NAME

        override val audienceFilterParams: Map<String, Any>
            get() =
                buildMap {
                    put("sendId", sendId)
                    contactPointId?.let { put("contactPointId", it) }
                }

        companion object {
            const val NAME = "notification_open"
        }
    }
}
