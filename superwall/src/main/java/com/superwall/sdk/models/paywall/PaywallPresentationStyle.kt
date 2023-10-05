package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun String.toPaywallPresentationStyle(): PaywallPresentationStyle? {
    return PaywallPresentationStyle.values().find { it.serialName == this }
}

private val PaywallPresentationStyle.serialName: String?
    get() = this::class.java.getField(this.name).getAnnotation(SerialName::class.java)?.value

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
    NONE
}
