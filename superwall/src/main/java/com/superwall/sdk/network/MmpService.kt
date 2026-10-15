package com.superwall.sdk.network

import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.attribution.AttributionProvider
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class MmpMatchRequest(
    val platform: String,
    val appUserId: String? = null,
    val deviceId: String? = null,
    val vendorId: String? = null,
    // The Android counterpart to iOS's `idfa`/`idfv`. Sourced from the developer-supplied
    // `AttributionProvider.GOOGLE_ADS` / `GOOGLE_APP_SET` integration attributes — the SDK
    // stopped collecting them itself in 2.5.5.
    val aaid: String? = null,
    val appSetId: String? = null,
    val installReferrerClickId: Long? = null,
    val appVersion: String? = null,
    val sdkVersion: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val deviceLocale: String? = null,
    val deviceLanguageCode: String? = null,
    val timezoneOffsetSeconds: Int? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val devicePixelRatio: Double? = null,
    val bundleId: String? = null,
    val clientTimestamp: String? = null,
    val metadata: Map<String, String>? = null,
    // The remaining third-party attribution identifiers the developer has set via
    // `Superwall.setIntegrationAttributes` — the MMP ids (`adjustId`, `appsflyerId`,
    // `singularDeviceId`, `kochavaDeviceId`, `tenjinId`) and friends. The advertising
    // identifiers are promoted out of this map into [aaid] and [appSetId].
    val integrationAttributes: Map<String, String>? = null,
)

@Serializable
data class MmpMatchResponse(
    val matched: Boolean,
    val confidence: AttributionMatchInfo.Confidence? = null,
    val matchScore: Double? = null,
    val clickId: Long? = null,
    val linkId: String? = null,
    val network: String? = null,
    val redirectUrl: String? = null,
    val queryParams: Map<String, JsonElement>? = null,
    val acquisitionAttributes: Map<String, JsonElement>? = null,
    val matchedAt: String? = null,
    val breakdown: Map<String, JsonElement>? = null,
)

/**
 * The advertising identifiers pulled out of the developer-supplied integration attributes,
 * plus whatever attributes remain.
 */
internal data class PromotedAdvertisingIds(
    val aaid: String?,
    val appSetId: String?,
    val remaining: Map<String, String>,
)

/**
 * Promotes the Google advertising identifiers out of the integration attributes and into their
 * own request fields, the way iOS sends `idfa` as a top-level field rather than loose metadata.
 *
 * Blank values are treated as absent, and a promoted key is removed from [remaining] so it isn't
 * sent twice.
 */
internal fun Map<String, String>.promoteAdvertisingIds(): PromotedAdvertisingIds =
    PromotedAdvertisingIds(
        aaid = this[AttributionProvider.GOOGLE_ADS.rawName]?.takeIf { it.isNotEmpty() },
        appSetId = this[AttributionProvider.GOOGLE_APP_SET.rawName]?.takeIf { it.isNotEmpty() },
        remaining =
            this - AttributionProvider.GOOGLE_ADS.rawName - AttributionProvider.GOOGLE_APP_SET.rawName,
    )

class MmpService(
    override val host: String,
    override val version: String,
    val factory: ApiFactory,
    json: Json,
    override val customHttpUrlConnection: CustomHttpUrlConnection,
) : NetworkService() {
    override suspend fun makeHeaders(
        isForDebugging: Boolean,
        requestId: String,
    ): Map<String, String> = factory.makeHeaders(isForDebugging, requestId)

    // Encode-only. Responses are decoded by [customHttpUrlConnection]'s own `Json`, which is
    // where decode leniency (`coerceInputValues`) has to be configured.
    private val json =
        Json(json) {
            namingStrategy = null
            explicitNulls = false
        }

    suspend fun matchInstall(request: MmpMatchRequest) =
        post<MmpMatchResponse>(
            "api/match",
            retryCount = 2,
            body = json.encodeToString(request).toByteArray(),
        )
}
