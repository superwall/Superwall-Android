package com.superwall.sdk.config.options

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.primitives.StateActor
import kotlinx.coroutines.CoroutineScope

/**
 * Sends the current ad consent to Superwall as device and config attributes.
 *
 * Facade over an [AdConsentState] actor: sends run one at a time in its queue, and
 * [publish] drops a send that a later one supersedes.
 */
internal class AdConsentPublisher(
    override val scope: CoroutineScope,
    override val tracker: suspend (Trackable) -> Unit,
    override val makeDeviceAttributes: suspend () -> HashMap<String, Any>,
    override val makeConfigAttributes: () -> Trackable,
    override val currentAdConsent: () -> ReportedAdConsent,
    override val eventTrackingBehavior: () -> EventTrackingBehavior,
    override val actor: StateActor<AdConsentContext, AdConsentState>,
) : AdConsentContext {
    /**
     * Re-sends device attributes when the ad consent [sent] carried differs from the current
     * consent. Called after every device attributes send.
     */
    fun reconcileAfterSend(sent: Map<String, Any>) {
        effect(AdConsentState.Actions.ReconcileAfterSend(sent))
    }

    /**
     * Re-sends device attributes when the current ad consent differs from the last one sent.
     * Called when the app comes to the foreground, like iOS.
     */
    fun republishIfChanged() {
        effect(AdConsentState.Actions.RepublishIfChanged)
    }
}
