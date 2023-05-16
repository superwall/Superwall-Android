package com.superwall.sdk.models.config

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
    var postback: PostbackRequest,
    @SerialName("appSessionTimeoutMs") var appSessionTimeout: Long,
    @SerialName("toggles") var featureFlags: List<FeatureFlags>,
    @SerialName("disablePreload") var preloadingDisabled: PreloadingDisabled,
    @SerialName("localization") var localizationConfig: LocalizationConfig,
    var requestId: String? = null,
    @Transient var locales: Set<String> = emptySet()
) {
    init {
        locales = localizationConfig.locales.map { it.locale }.toSet()
    }
}
