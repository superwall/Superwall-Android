package com.superwall.sdk.models.paywall

import java.net.URL

data class WebArchive(
    val mainResource: WebArchiveResource,
    val subResources: List<WebArchiveResource>
)

class WebArchiveResource(
    val url: URL,
    val data: ByteArray,
    val mimeType: String
)
