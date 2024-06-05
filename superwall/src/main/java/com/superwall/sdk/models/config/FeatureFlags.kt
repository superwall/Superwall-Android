package com.superwall.sdk.models.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RawFeatureFlag(
    val key: String,
    val enabled: Boolean,
)

@Serializable
data class FeatureFlags(
    @SerialName("enable_session_events") var enableSessionEvents: Boolean,
    @SerialName("enable_postback") var enablePostback: Boolean,
    @SerialName("enable_userid_seed") var enableUserIdSeed: Boolean,
    @SerialName("disable_verbose_events") var disableVerboseEvents: Boolean,
)

fun List<RawFeatureFlag>.value(
    key: String,
    default: Boolean,
): Boolean {
    val featureFlag = this.firstOrNull { it.key == key }
    return featureFlag?.enabled ?: default
}
