package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.config.getConfig
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.storage.DidCompleteMMPInstallAttributionRequest
import com.superwall.sdk.storage.MMPAcquisitionData
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Where this launch's install match is.
 */
internal data class MMPAttributionState(
    val match: Match = Match.None,
) {
    sealed class Match {
        /** No install match this launch. */
        object None : Match()

        /** This launch will match, but the match isn't set up yet. */
        object Pending : Match()

        /** Set up and waiting for config to turn the MMP on. */
        object Waiting : Match()

        /** Skipped because the app opted out of tracking. Tried again when it opts back in. */
        object Skipped : Match()

        object Running : Match()

        object Finished : Match()
    }

    /** `true` from when this launch's install match is set up until it finishes or is skipped. */
    val isMatchPending: Boolean
        get() = match is Match.Pending || match is Match.Waiting || match is Match.Running

    internal sealed class Updates(
        override val reduce: (MMPAttributionState) -> MMPAttributionState,
    ) : Reducer<MMPAttributionState> {
        object MarkPending : Updates({ state ->
            if (state.match is Match.None) state.copy(match = Match.Pending) else state
        })

        object SetUp : Updates({ state ->
            when (state.match) {
                is Match.Running, is Match.Finished -> state
                else -> state.copy(match = Match.Waiting)
            }
        })

        object Skip : Updates({ state -> state.copy(match = Match.Skipped) })

        object Start : Updates({ state -> state.copy(match = Match.Running) })

        object Finish : Updates({ state -> state.copy(match = Match.Finished) })
    }

    internal sealed class Actions(
        override val execute: suspend MMPAttributionContext.() -> Unit,
    ) : TypedAction<MMPAttributionContext> {
        /**
         * Sets up the match and starts it once config turns the MMP on, which may be straight
         * away. The wait for config runs off the queue so it doesn't hold up other actions.
         */
        object MatchOnceEnabled : Actions({
            update(Updates.SetUp)
            sideEffect {
                configState.first { it.getConfig()?.isMmpEnabled == true }
                effect(StartIfEnabled)
            }
        })

        /**
         * Starts this install's match if config has the MMP on and it hasn't started yet.
         * Called when config arrives and when the app turns tracking back on.
         */
        object StartIfEnabled : Actions(
            exec@{
                if (!isMmpEnabled) return@exec
                if (state.value.match !is Match.Waiting && state.value.match !is Match.Skipped) {
                    return@exec
                }
                // Skip matching when the app has opted out of all event collection. The
                // `/api/match` call and the `acquisition_*` attribute writes happen outside the
                // event queue, so queue-level suppression wouldn't catch them. Don't hold
                // paywalls up in the meantime.
                if (eventTrackingBehavior() == EventTrackingBehavior.NONE) {
                    update(Updates.Skip)
                    return@exec
                }
                if (storage.read(DidCompleteMMPInstallAttributionRequest) == true) {
                    update(Updates.Finish)
                    return@exec
                }
                update(Updates.Start)
                effect(MatchInstall)
            },
        )

        /**
         * Fires the install-attribution match and applies its result.
         *
         * On a successful response the resolved `acquisition_*` payload is cached
         * (install-scoped, so it survives `reset`) and merged into the current user's
         * attributes, and the request is recorded as complete so it isn't repeated. A failed
         * request is tried again on the next launch while the install window is open.
         */
        object MatchInstall : Actions({
            // Finish even if something here throws, or `isMatchPending` would stay true and
            // every paywall using `acquisition_*` would wait out its full timeout.
            try {
                when (val result = sendMatchRequest(readInstallReferrerClickId())) {
                    is Either.Success -> {
                        val response = result.value

                        response.acquisitionAttributes?.let {
                            persist(MMPAcquisitionData, it)
                            val attributes = it.toAttributeValues()
                            val currentAttributes = userAttributes()
                            val hasChanges =
                                attributes.any { (key, value) ->
                                    currentAttributes[key]?.toString() != value.toString()
                                }
                            if (hasChanges) {
                                setUserAttributes(attributes)
                            }
                        }

                        tracker(
                            InternalSuperwallEvent.AttributionMatch(
                                AttributionMatchInfo(
                                    provider = AttributionMatchInfo.Provider.MMP,
                                    matched = response.matched,
                                    source =
                                        response.acquisitionAttributes.readString("acquisition_source")
                                            ?: response.network,
                                    confidence = response.confidence,
                                    matchScore = response.matchScore,
                                    reason = response.breakdown.readString("reason"),
                                ),
                            ),
                        )

                        // A successful response means the request was processed, even if no
                        // attribution match was found.
                        persist(DidCompleteMMPInstallAttributionRequest, true)
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
                    }
                }
            } finally {
                update(Updates.Finish)
            }
        })
    }
}

internal fun Map<String, JsonElement>.toAttributeValues(): Map<String, Any> =
    mapNotNull { (key, value) ->
        value.toAttributeValue()?.let { key to it }
    }.toMap()

private fun JsonElement.toAttributeValue(): Any? =
    when (this) {
        is JsonNull -> null

        is JsonPrimitive -> {
            val booleanValue = booleanOrNull
            val longValue = longOrNull
            val doubleValue = doubleOrNull

            when {
                isString -> contentOrNull
                booleanValue != null -> booleanValue
                longValue != null -> longValue
                doubleValue != null -> doubleValue
                else -> contentOrNull
            }
        }

        else -> toString()
    }

private fun Map<String, JsonElement>?.readString(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
