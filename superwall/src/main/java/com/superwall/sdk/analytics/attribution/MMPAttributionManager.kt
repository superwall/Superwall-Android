package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.MmpMatchResponse
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.MMPAcquisitionData
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the MMP (mobile measurement partner) install-attribution flow: firing the
 * match, persisting and re-applying the resolved install-scoped `acquisition_*`
 * attributes, and tracking the outcome. Mirrors how `AttributionPoster` owns the
 * Apple Search Ads flow on iOS.
 *
 * Facade over an [MMPAttributionState] actor, whose actions run the match.
 */
internal class MMPAttributionManager(
    override val storage: Storage,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    override val userAttributes: () -> Map<String, Any>,
    override val setUserAttributes: (Map<String, Any?>) -> Unit,
    override val sendMatchRequest: suspend (Long?) -> Either<MmpMatchResponse, NetworkError>,
    override val readInstallReferrerClickId: suspend () -> Long?,
    override val eventTrackingBehavior: () -> EventTrackingBehavior,
    override val configState: StateFlow<ConfigState>,
    override val scope: CoroutineScope,
    override val actor: StateActor<MMPAttributionContext, MMPAttributionState>,
) : MMPAttributionContext {
    companion object {
        /** How long a paywall waits for an install match that's still running. */
        val presentationWaitTimeout: Duration = 2.seconds

        fun usesAcquisitionAttributes(trigger: Trigger): Boolean =
            trigger.rules.any { rule ->
                listOfNotNull(rule.expression, rule.expressionCEL, rule.expressionJs)
                    .any { it.contains("acquisition_") }
            }
    }

    /**
     * Marks this launch's install match as pending, so a paywall that's requested
     * before the match is set up still waits for it.
     */
    fun markMatchPending() {
        update(MMPAttributionState.Updates.MarkPending)
    }

    /**
     * Starts the install match once config turns the MMP on for this app, which may be
     * straight away. It's off by default, so it never fires unless the backend enables it.
     * It's skipped while the app has opted out of tracking, and tried again when it opts
     * back in, via [startMatchIfEnabled].
     */
    fun matchInstallOnceEnabled() {
        effect(MMPAttributionState.Actions.MatchOnceEnabled)
    }

    /**
     * Starts this install's match if config has the MMP on and it hasn't started
     * yet. Called when config arrives and when the app turns tracking back on.
     */
    fun startMatchIfEnabled() {
        effect(MMPAttributionState.Actions.StartIfEnabled)
    }

    /**
     * The cached MMP `acquisition_*` payload as attribute values, or empty if no match
     * ever resolved. The identity reset merges these into every new user, since the
     * backend match only succeeds within the 7-day install window.
     */
    fun cachedAcquisitionAttributes(): Map<String, Any?> = storage.read(MMPAcquisitionData)?.toAttributeValues().orEmpty()

    /**
     * On a first launch, a paywall can be requested while the install match is still
     * running. If the MMP is on and the placement's audiences use `acquisition_*`
     * attributes, this waits for the match, up to [timeout], so they're there when the
     * audiences are checked. Returns straight away otherwise.
     */
    suspend fun waitForPendingMatch(
        trigger: Trigger?,
        timeout: Duration = presentationWaitTimeout,
    ) {
        if (trigger == null || !state.value.isMatchPending) {
            return
        }
        if (!isMmpEnabled) {
            return
        }
        if (!usesAcquisitionAttributes(trigger)) {
            return
        }
        withTimeoutOrNull(timeout) {
            state.first { !it.isMatchPending }
        }
    }
}
