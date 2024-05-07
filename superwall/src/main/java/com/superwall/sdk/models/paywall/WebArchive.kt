package com.superwall.sdk.models.paywall

data class WebArchive(
    val mainResource: WebArchiveMainResource,
    val webSubresources: List<WebArchiveResource>
)

data class WebArchiveResource(
    val url: String,
    val data: String,
    val mimeType: String
)

data class WebArchiveMainResource(
    val baseResource: WebArchiveResource
)