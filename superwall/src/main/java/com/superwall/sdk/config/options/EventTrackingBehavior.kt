package com.superwall.sdk.config.options

/**
 * Controls which events are sent to the Superwall servers.
 *
 * Use [SuperwallOptions.eventTrackingBehavior] or set [com.superwall.sdk.Superwall.eventTrackingBehavior]
 * at runtime to change event collection at any time.
 *
 * - [ALL]: All events are tracked (default).
 * - [SUPERWALL_ONLY]: Only internal Superwall events are tracked. User-initiated
 *   `Superwall.track` calls, trigger fires, and user-attribute updates are suppressed.
 *   Equivalent to the deprecated `isExternalDataCollectionEnabled = false`.
 * - [NONE]: No events are sent to the Superwall servers.
 */
enum class EventTrackingBehavior(
    val description: String,
) {
    /** All events are tracked. This is the default. */
    ALL("all"),

    /**
     * Only internal Superwall events are tracked.
     *
     * User-initiated tracking calls, trigger-fire events, and user-attribute
     * updates are suppressed. All other internal SDK events continue to be sent.
     */
    SUPERWALL_ONLY("superwallOnly"),

    /** No events are sent to the Superwall servers. */
    NONE("none"),
    ;

    override fun toString(): String = description
}
