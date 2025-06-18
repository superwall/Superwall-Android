package com.superwall.sdk.paywall.archive.models

sealed interface ArchivePart {
    val url: String
    val mimeType: String
    val content: ByteArray
    val contentId: String

    data class Resource(
        override val url: String,
        override val mimeType: String,
        override val content: ByteArray,
    ) : ArchivePart {
        override val contentId: String = ""
    }

    data class Document(
        override val url: String,
        override val mimeType: String,
        override val content: ByteArray,
    ) : ArchivePart {
        override val contentId: String = ArchiveKeys.ContentId.MAIN_DOCUMENT.key
    }
}
