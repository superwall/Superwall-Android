package com.superwall.sdk.config.options

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.primitives.StoreContext

/**
 * All dependencies available to ad consent [AdConsentState.Actions].
 */
internal interface AdConsentContext : StoreContext<AdConsentState, AdConsentContext> {
    val tracker: suspend (Trackable) -> Unit
    val makeDeviceAttributes: suspend () -> HashMap<String, Any>
    val makeConfigAttributes: () -> Trackable
    val currentAdConsent: () -> ReportedAdConsent
    val eventTrackingBehavior: () -> EventTrackingBehavior

    /** Applies a tracking behavior to the events queue, returning once it has. */
    val updateEventsQueue: suspend (EventTrackingBehavior) -> Unit

    /**
     * Queues a send of the current ad consent. One superseded by a later publish is dropped,
     * so an older snapshot is never tracked after a newer one.
     */
    fun publish() {
        update(AdConsentState.Updates.Queued)
        effect(AdConsentState.Actions.Publish)
    }
}
