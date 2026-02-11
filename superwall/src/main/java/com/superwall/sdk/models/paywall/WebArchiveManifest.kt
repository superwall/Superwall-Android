package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebArchiveManifest(
    @SerialName("use") val use: Usage,
    @SerialName("document") val document: Document,
    @SerialName("resources") val resources: List<Resource>,
) {
    sealed interface ManifestPart {
        val url: String
        val mimeType: String
    }

    @Serializable
    enum class Usage {
        @SerialName("IF_AVAILABLE_ON_PAYWALL_OPEN")
        IF_AVAILABLE_ON_PAYWALL_OPEN,

        @SerialName("NEVER")
        NEVER,

        @SerialName("ALWAYS")
        ALWAYS,
    }

    @Serializable
    data class Document(
        @SerialName("url")
        override val url: String,
        @SerialName("mime_type")
        override val mimeType: String,
    ) : ManifestPart

    @Serializable
    data class Resource(
        @SerialName("url")
        override val url: String,
        @SerialName("mime_type")
        override val mimeType: String,
    ) : ManifestPart
}
