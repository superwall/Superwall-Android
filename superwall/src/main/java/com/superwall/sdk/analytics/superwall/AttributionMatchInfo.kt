package com.superwall.sdk.analytics.superwall

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Information about an install attribution result emitted by Superwall.
 */
data class AttributionMatchInfo(
    val provider: Provider,
    val matched: Boolean,
    val source: String? = null,
    val confidence: Confidence? = null,
    val matchScore: Double? = null,
    val reason: String? = null,
) {
    /**
     * The attribution provider that produced the result.
     */
    @Serializable
    enum class Provider(
        val rawName: String,
    ) {
        @SerialName("mmp")
        MMP("mmp"),

        @SerialName("apple_search_ads")
        APPLE_SEARCH_ADS("apple_search_ads"),
    }

    /**
     * The confidence level returned by the attribution provider.
     */
    @Serializable
    enum class Confidence(
        val rawName: String,
    ) {
        @SerialName("high")
        HIGH("high"),

        @SerialName("medium")
        MEDIUM("medium"),

        @SerialName("low")
        LOW("low"),
    }
}
