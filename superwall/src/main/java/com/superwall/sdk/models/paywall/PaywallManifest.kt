package com.superwall.sdk.models.paywall

import com.superwall.sdk.models.serialization.URLSerializer
import kotlinx.serialization.Serializable
import java.net.URL

@Serializable
enum class ArchivalManifestUsage {
    ALWAYS, NEVER, IF_AVAILABLE_ON_PAYWALL_OPEN
}

@Serializable
data class ArchivalManifest(
    val use: ArchivalManifestUsage,
    val document: ArchivalManifestItem,
    val resources: List<ArchivalManifestItem>
)

@Serializable
data class ArchivalManifestItem(
    val url: @Serializable(with = URLSerializer::class) URL,
    val mimeType: String
)
