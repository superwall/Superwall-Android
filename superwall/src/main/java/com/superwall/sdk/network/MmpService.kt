package com.superwall.sdk.network

import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.attribution.AttributionProvider
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MmpMatchRequest(
    @SerialName("platform") val platform: String,
    @SerialName("appUserId") val appUserId: String? = null,
    @SerialName("deviceId") val deviceId: String? = null,
    @SerialName("vendorId") val vendorId: String? = null,
    // The Android counterpart to iOS's `idfa`/`idfv`. Sourced from the developer-supplied
    // `AttributionProvider.GOOGLE_ADS` / `GOOGLE_APP_SET` integration attributes — the SDK
    // stopped collecting them itself in 2.5.5.
    @SerialName("aaid") val aaid: String? = null,
    @SerialName("appSetId") val appSetId: String? = null,
    // Sent under iOS's name too, so the backend can treat both platforms' vendor ids alike.
    @SerialName("idfv") val idfv: String? = null,
    // Always sent, as on iOS. Android has no app-level tracking prompt like ATT, so both are true.
    @EncodeDefault
    @SerialName("advertiserTrackingEnabled") val advertiserTrackingEnabled: Boolean = true,
    @EncodeDefault
    @SerialName("applicationTrackingEnabled") val applicationTrackingEnabled: Boolean = true,
    @SerialName("installReferrerClickId") val installReferrerClickId: Long? = null,
    @SerialName("appVersion") val appVersion: String? = null,
    @SerialName("sdkVersion") val sdkVersion: String? = null,
    @SerialName("osVersion") val osVersion: String? = null,
    @SerialName("deviceModel") val deviceModel: String? = null,
    @SerialName("deviceLocale") val deviceLocale: String? = null,
    @SerialName("deviceLanguageCode") val deviceLanguageCode: String? = null,
    @SerialName("timezoneOffsetSeconds") val timezoneOffsetSeconds: Int? = null,
    @SerialName("screenWidth") val screenWidth: Int? = null,
    @SerialName("screenHeight") val screenHeight: Int? = null,
    @SerialName("devicePixelRatio") val devicePixelRatio: Double? = null,
    @SerialName("bundleId") val bundleId: String? = null,
    @SerialName("clientTimestamp") val clientTimestamp: String? = null,
    @SerialName("metadata") val metadata: Map<String, String>? = null,
    // The remaining third-party attribution identifiers the developer has set via
    // `Superwall.setIntegrationAttributes` — the MMP ids (`adjustId`, `appsflyerId`,
    // `singularDeviceId`, `kochavaDeviceId`, `tenjinId`) and friends. The advertising
    // identifiers are promoted out of this map into [aaid] and [appSetId].
    @SerialName("integrationAttributes") val integrationAttributes: Map<String, String>? = null,
)

@Serializable
data class MmpMatchResponse(
    @SerialName("matched") val matched: Boolean,
    @SerialName("confidence") val confidence: AttributionMatchInfo.Confidence? = null,
    @SerialName("matchScore") val matchScore: Double? = null,
    @SerialName("clickId") val clickId: Long? = null,
    @SerialName("linkId") val linkId: String? = null,
    @SerialName("network") val network: String? = null,
    @SerialName("redirectUrl") val redirectUrl: String? = null,
    @SerialName("queryParams") val queryParams: Map<String, JsonElement>? = null,
    @SerialName("acquisitionAttributes") val acquisitionAttributes: Map<String, JsonElement>? = null,
    @SerialName("matchedAt") val matchedAt: String? = null,
    @SerialName("breakdown") val breakdown: Map<String, JsonElement>? = null,
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
