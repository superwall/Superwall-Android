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

@Serializable
sealed class PaywallPresentationStyleExpanded(
    val rawValue: String,
) {
    @Serializable
    @SerialName("MODAL")
    data object Modal : PaywallPresentationStyleExpanded("MODAL")

    @Serializable
    @SerialName("FULLSCREEN")
    data object Fullscreen : PaywallPresentationStyleExpanded("FULLSCREEN")

    @Serializable
    @SerialName("NO_ANIMATION")
    data object FullscreenNoAnimation : PaywallPresentationStyleExpanded("NO_ANIMATION")

    @Serializable
    @SerialName("PUSH")
    data object Push : PaywallPresentationStyleExpanded("PUSH")

    @Serializable
    @SerialName("DRAWER")
    data class Drawer(
        val height: Double,
    ) : PaywallPresentationStyleExpanded("DRAWER")

    @Serializable
    @SerialName("NONE")
    data object None : PaywallPresentationStyleExpanded("NONE")

    @Serializable
    @SerialName("POPUP")
    data object Popup : PaywallPresentationStyleExpanded("POPUP")

    // Helper methods for Android Intent serialization only
    fun toIntentString(): String =
        when (this) {
            is Modal -> "MODAL"
            is Fullscreen -> "FULLSCREEN"
            is FullscreenNoAnimation -> "NO_ANIMATION"
            is Push -> "PUSH"
            is Drawer -> "DRAWER:$height"
            is None -> "NONE"
            is Popup -> "POPUP"
        }

    companion object {
        fun fromIntentString(value: String): PaywallPresentationStyleExpanded =
            when {
                value == "MODAL" -> Modal
                value == "FULLSCREEN" -> Fullscreen
                value == "NO_ANIMATION" -> FullscreenNoAnimation
                value == "PUSH" -> Push
                value.startsWith("DRAWER:") -> {
                    val height = value.substringAfter("DRAWER:").toDoubleOrNull() ?: 50.0
                    Drawer(height)
                }
                value == "DRAWER" -> Drawer(50.0) // Fallback
                value == "NONE" -> None
                value == "POPUP" -> Popup
                else -> None
            }
    }
}
