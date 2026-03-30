package com.superwall.sdk.analytics.superwall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
