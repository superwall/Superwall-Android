package com.superwall.sdk.models.config

import com.superwall.sdk.models.serialization.URLSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URL

@Serializable
data class WebArchiveManifest(
    @SerialName("use") val use: Usage,
    @SerialName("document") val document: Document,
    @SerialName("resources") val resources: List<Resource>
) {
    sealed interface ManifestPart {
        val url: URL
        val mimeType: String
    }

    @Serializable
    enum class Usage {
        @SerialName("IF_AVAILABLE_ON_PAYWALL_OPEN")
        IF_AVAILABLE_ON_PAYWALL_OPEN,

        @SerialName("NEVER")
        NEVER,

        @SerialName("ALWAYS")
        ALWAYS
    }

    @Serializable
    data class Document(
        @SerialName("url")
        override val url: @Serializable(with = URLSerializer::class) URL,
        @SerialName("mime_type")
        override val mimeType: String
    ) : ManifestPart

    @Serializable
    data class Resource(
        @SerialName("url")
        override val url: @Serializable(with = URLSerializer::class) URL,
        @SerialName("mime_type")
        override val mimeType: String
    ) : ManifestPart
}
