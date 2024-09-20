package com.superwall.sdk.models.config

import ComputedPropertyRequest
import com.superwall.sdk.models.SerializableEntity
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.postback.PostbackRequest
import com.superwall.sdk.models.triggers.Trigger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Config(
    @SerialName("triggerOptions") var triggers: Set<Trigger>,
    @SerialName("paywallResponses") var paywalls: List<Paywall>,
    var logLevel: Int,
    @SerialName("postback")
    var postback: PostbackRequest,
    @SerialName("appSessionTimeoutMs") var appSessionTimeout: Long,
    @SerialName("toggles") var rawFeatureFlags: List<RawFeatureFlag>,
    @SerialName("disablePreload") var preloadingDisabled: PreloadingDisabled,
    @SerialName("localization") var localizationConfig: LocalizationConfig,
    var requestId: String? = null,
    @Transient var locales: Set<String> = emptySet(),
    @SerialName("build_id") val buildId: String,
) : SerializableEntity {
    init {
        locales = localizationConfig.locales.map { it.locale }.toSet()
    }

    val allComputedProperties: List<ComputedPropertyRequest>
        get() =
            triggers.flatMap { trigger ->
                trigger.rules.flatMap { rule ->
                    rule.computedPropertyRequests
                }
            }

    val featureFlags: FeatureFlags
        get() =
            FeatureFlags(
                enableMultiplePaywallUrls =
                    rawFeatureFlags.find { it.key == "enable_multiple_paywall_urls" }?.enabled
                        ?: false,
                enableConfigRefresh =
                    rawFeatureFlags.find { it.key == "enable_config_refresh" }?.enabled
                        ?: false,
                enableSessionEvents =
                    rawFeatureFlags.find { it.key == "enable_session_events" }?.enabled
                        ?: false,
                enablePostback =
                    rawFeatureFlags.find { it.key == "enable_postback" }?.enabled
                        ?: false,
                enableUserIdSeed =
                    rawFeatureFlags.find { it.key == "enable_userid_seed" }?.enabled
                        ?: false,
                disableVerboseEvents =
                    rawFeatureFlags.find { it.key == "disable_verbose_events" }?.enabled
                        ?: false,
            )

    companion object {
        fun stub(): Config =
            Config(
                triggers = setOf(Trigger.stub()),
                paywalls = listOf(Paywall.stub()),
                logLevel = 0,
                postback = PostbackRequest.stub(),
                appSessionTimeout = 3600000,
                rawFeatureFlags = emptyList(),
                preloadingDisabled = PreloadingDisabled.stub(),
                localizationConfig = LocalizationConfig(locales = emptyList()),
                buildId = "stub-build-id",
            )
    }
}
