package com.superwall.sdk.network

import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.dependencies.ApiFactory
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

    private val json =
        Json(json) {
            namingStrategy = null
            explicitNulls = false
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    suspend fun matchInstall(request: MmpMatchRequest) =
        post<MmpMatchResponse>(
            "api/match",
            retryCount = 2,
            body = json.encodeToString(request).toByteArray(),
        )
}
