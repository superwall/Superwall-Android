package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PaywallPresentationStyle(
    val rawValue: String,
) {
    @SerialName("MODAL")
    MODAL("MODAL"),

    @SerialName("FULLSCREEN")
    FULLSCREEN("FULLSCREEN"),

    @SerialName("NO_ANIMATION")
    FULLSCREEN_NO_ANIMATION("NO_ANIMATION"),

    @SerialName("PUSH")
    PUSH("PUSH"),

    @SerialName("DRAWER")
    DRAWER("DRAWER"),

    @SerialName("NONE")
    NONE("NONE"),

    @SerialName("DIALOG")
    DIALOG("DIALOG"),
}
