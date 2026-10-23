package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.MmpMatchResponse
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.MMPAcquisitionData
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the MMP (mobile measurement partner) install-attribution flow: firing the
 * match, persisting and re-applying the resolved install-scoped `acquisition_*`
 * attributes, and tracking the outcome.
 *
 * [sendMatchRequest] is used purely as transport — it sends the request and returns the
 * decoded response. Everything attribution-specific lives here, mirroring how
 * `AttributionPoster` owns the Apple Search Ads flow on iOS.
 *
 * Facade over an [MMPAttributionState] actor, which tracks where this launch's match is.
 */
internal class MMPAttributionManager(
    override val storage: Storage,
    private val identityManager: IdentityManager,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    private val setUserAttributes: (Map<String, Any?>) -> Unit,
    private val sendMatchRequest: suspend (Long?) -> Either<MmpMatchResponse, NetworkError>,
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
     * Fires the install-attribution match and applies its result.
     *
     * On a successful response the resolved `acquisition_*` payload is cached
     * (install-scoped, so it survives [com.superwall.sdk.Superwall.reset]) and merged
     * into the current user's attributes. Returns whether the request completed — the
     * caller uses this to persist the completion flag so the match isn't repeated.
     */
    suspend fun matchInstall(installReferrerClickId: Long?): Boolean =
        when (val result = sendMatchRequest(installReferrerClickId)) {
            is Either.Success -> {
                val response = result.value

                response.acquisitionAttributes?.let {
                    // Cache the resolved payload (install-scoped) so it can be re-applied to a
                    // new user's attributes after `reset` without re-matching against the backend.
                    storage.write(MMPAcquisitionData, it)
                    mergeAcquisitionAttributesIfNeeded(it)
                }

                tracker(
                    InternalSuperwallEvent.AttributionMatch(
                        AttributionMatchInfo(
                            provider = AttributionMatchInfo.Provider.MMP,
                            matched = response.matched,
                            source =
                                readJsonString(response.acquisitionAttributes, "acquisition_source")
                                    ?: response.network,
                            confidence = response.confidence,
                            matchScore = response.matchScore,
                            reason = readJsonString(response.breakdown, "reason"),
                        ),
                    ),
                )

                // A successful response means the request was processed, even if no
                // attribution match was found.
                true
            }

            is Either.Failure -> {
                tracker(
                    InternalSuperwallEvent.AttributionMatch(
                        AttributionMatchInfo(
                            provider = AttributionMatchInfo.Provider.MMP,
                            matched = false,
                            reason = "request_failed",
                        ),
                    ),
                )
                false
            }
        }

    /**
     * The cached MMP `acquisition_*` payload as attribute values, or empty if no match
     * ever resolved. The identity reset merges these into every new user, since the
     * backend match only succeeds within the 7-day install window.
     */
    fun cachedAcquisitionAttributes(): Map<String, Any?> = storage.read(MMPAcquisitionData)?.toAttributeValues().orEmpty()

    /**
     * Calls [startMatch] once config turns the MMP on for this app, which may be
     * straight away. It's off by default, so it never fires unless the backend
     * enables it.
     *
     * [startMatch] returns the running match, or null if it skipped it because the
     * app has opted out of tracking. A skipped match is tried again when the app
     * opts back in, via [startMatchIfEnabled].
     */
    fun matchInstallOnceEnabled(startMatch: () -> Job?) {
        effect(MMPAttributionState.Actions.MatchOnceEnabled(startMatch))
    }

    /**
     * Starts this install's match if config has the MMP on and it hasn't started
     * yet. Called when config arrives and when the app turns tracking back on.
     */
    fun startMatchIfEnabled() {
        effect(MMPAttributionState.Actions.StartIfEnabled)
    }

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

    private fun mergeAcquisitionAttributesIfNeeded(acquisitionAttributes: Map<String, JsonElement>) {
        val attributes = acquisitionAttributes.toAttributeValues()

        if (attributes.isEmpty()) {
            return
        }

        val currentAttributes = identityManager.userAttributes
        val hasChanges =
            attributes.any { (key, value) ->
                currentAttributes[key]?.toString() != value.toString()
            }

        if (!hasChanges) {
            return
        }

        setUserAttributes(attributes)
    }

    private fun Map<String, JsonElement>.toAttributeValues(): Map<String, Any> =
        mapNotNull { (key, value) ->
            jsonElementToValue(value)?.let { key to it }
        }.toMap()

    private fun jsonElementToValue(value: JsonElement): Any? =
        when {
            value is JsonNull -> null

            value is JsonPrimitive -> {
                val booleanValue = value.booleanOrNull
                val longValue = value.longOrNull
                val doubleValue = value.doubleOrNull

                when {
                    value.isString -> value.contentOrNull
                    booleanValue != null -> booleanValue
                    longValue != null -> longValue
                    doubleValue != null -> doubleValue
                    else -> value.contentOrNull
                }
            }

            else -> value.toString()
        }

    private fun readJsonString(
        value: Map<String, JsonElement>?,
        key: String,
    ): String? = (value?.get(key) as? JsonPrimitive)?.contentOrNull
}
