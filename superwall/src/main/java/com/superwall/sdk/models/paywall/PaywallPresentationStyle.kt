package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PaywallPresentationStyle {
    @SerialName("MODAL")
    MODAL,

    @SerialName("FULLSCREEN")
    FULLSCREEN,

    @SerialName("NO_ANIMATION")
    FULLSCREEN_NO_ANIMATION,

    @SerialName("PUSH")
    PUSH,

    @SerialName("DRAWER")
    DRAWER,

    @SerialName("NONE")
    NONE,
}
