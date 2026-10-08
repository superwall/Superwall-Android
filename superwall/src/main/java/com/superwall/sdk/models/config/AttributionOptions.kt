package com.superwall.sdk.models.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Attribution features the backend has turned on for this app. */
@Serializable
data class AttributionOptions(
    @SerialName("mmp") val mmp: MmpAttributionOptions? = null,
)

/** Superwall's install attribution (MMP). Off unless the backend enables it. */
@Serializable
data class MmpAttributionOptions(
    @SerialName("enabled") val enabled: Boolean = false,
)
