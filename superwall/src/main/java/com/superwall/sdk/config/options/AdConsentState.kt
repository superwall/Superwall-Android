package com.superwall.sdk.config.options

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import kotlinx.coroutines.Job

/**
 * What the ad consent publisher has queued and last sent.
 */
internal data class AdConsentState(
    /** Publishes queued but not yet run. A send is dropped while a newer one is queued behind it. */
    val queuedPublishes: Int = 0,
    /** The ad consent attributes of the latest device attributes send, or null before the first. */
    val lastSent: Map<String, Any>? = null,
) {
    internal sealed class Updates(
        override val reduce: (AdConsentState) -> AdConsentState,
    ) : Reducer<AdConsentState> {
        object Queued : Updates({ state -> state.copy(queuedPublishes = state.queuedPublishes + 1) })

        object Dequeued : Updates({ state -> state.copy(queuedPublishes = state.queuedPublishes - 1) })

        data class Sent(
            val consentAttributes: Map<String, Any>,
        ) : Updates({ state -> state.copy(lastSent = consentAttributes) })
    }

    internal sealed class Actions(
        override val execute: suspend AdConsentContext.() -> Unit,
    ) : TypedAction<AdConsentContext> {
        /** Sends the current ad consent as device and config attributes. */
        data class Publish(
            val after: Job?,
        ) : Actions(
                exec@{
                    after?.join()
                    update(Updates.Dequeued)
                    if (state.value.queuedPublishes > 0) {
                        // A newer publish will send the latest consent.
                        return@exec
                    }
                    tracker(InternalSuperwallEvent.DeviceAttributes(makeDeviceAttributes()))
                    tracker(makeConfigAttributes())
                },
            )

        /**
         * Records the consent that a device attributes send carried, and re-sends it when it
         * differs from the current consent. Runs after every device attributes send.
         */
        data class ReconcileAfterSend(
            val sent: Map<String, Any>,
        ) : Actions(
                exec@{
                    if (eventTrackingBehavior() == EventTrackingBehavior.NONE) {
                        return@exec
                    }
                    if (sent.keys.any { it in AD_CONSENT_ATTRIBUTE_KEYS }) {
                        update(Updates.Sent(sent.filterKeys { it in AD_CONSENT_ATTRIBUTE_KEYS }))
                    }
                    if (currentAdConsent().differsFrom(sent)) {
                        publish()
                    }
                },
            )

        /** Re-sends the current ad consent when it differs from the last one sent. */
        object RepublishIfChanged : Actions({
            if (currentAdConsent().needsRepublish(state.value.lastSent, eventTrackingBehavior())) {
                publish()
            }
        })
    }
}
