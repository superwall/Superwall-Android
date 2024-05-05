package com.superwall.sdk.webarchive.archive

import com.superwall.sdk.webarchive.models.ArchiveKeys

sealed interface ArchivePart {
    val url: String
    val mimeType: String
    val content: String
    val contentId: String

    data class Resource(
        override val url: String,
        override val mimeType: String,
        override val content: String,
    ): ArchivePart {
        override val contentId: String = ""
    }

    data class Document(
        override val url: String,
        override val mimeType: String,
        override val content: String
    ): ArchivePart {
        override val contentId: String = ArchiveKeys.ContentId.MAIN_DOCUMENT.key
    }

}
