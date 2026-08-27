package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.Either
import com.superwall.sdk.network.MmpMatchResponse
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.MMPAcquisitionData
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Owns the MMP (mobile measurement partner) install-attribution flow: firing the
 * match, persisting and re-applying the resolved install-scoped `acquisition_*`
 * attributes, and tracking the outcome.
 *
 * [sendMatchRequest] is used purely as transport — it sends the request and returns the
 * decoded response. Everything attribution-specific lives here, mirroring how
 * `AttributionPoster` owns the Apple Search Ads flow on iOS.
 */
class MMPAttributionManager(
    private val storage: LocalStorage,
    private val identityManager: IdentityManager,
    private val track: suspend (TrackableSuperwallEvent) -> Unit,
    private val setUserAttributes: (Map<String, Any?>) -> Unit,
    private val sendMatchRequest: suspend (Long?) -> Either<MmpMatchResponse, NetworkError>,
) {
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

                track(
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
                track(
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
     * Re-applies the cached MMP `acquisition_*` payload to the current user's attributes.
     *
     * Called from [com.superwall.sdk.Superwall.reset] after user files are wiped so the new
     * user identity inherits the install-scoped attribution without re-matching against the
     * backend (which only succeeds within the 7-day install window). No-op if no match ever
     * resolved.
     */
    fun reapplyCachedAcquisitionAttributes() {
        val cached = storage.read(MMPAcquisitionData) ?: return
        mergeAcquisitionAttributesIfNeeded(cached)
    }

    private fun mergeAcquisitionAttributesIfNeeded(acquisitionAttributes: Map<String, JsonElement>) {
        val attributes =
            acquisitionAttributes
                .mapNotNull { (key, value) ->
                    jsonElementToValue(value)?.let { key to it }
                }.toMap()

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
