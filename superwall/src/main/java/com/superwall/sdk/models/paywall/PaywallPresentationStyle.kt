package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        @SerialName("height")
        val height: Double,
        @SerialName("corner_radius")
        val cornerRadius: Double,
    ) : PaywallPresentationStyleExpanded("DRAWER")

    @Serializable
    @SerialName("NONE")
    data object None : PaywallPresentationStyleExpanded("NONE")

    @Serializable
    @SerialName("POPUP")
    data class Popup(
        @SerialName("height")
        val height: Double,
        @SerialName("width")
        val width: Double,
    ) : PaywallPresentationStyleExpanded("POPUP")

    // Helper methods for Android Intent serialization only
    fun toIntentString(json: Json): String = json.encodeToString(this)

    companion object {
        fun fromIntentString(
            json: Json,
            value: String,
        ): PaywallPresentationStyleExpanded = json.decodeFromString(value)
    }
}
