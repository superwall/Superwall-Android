package com.superwall.sdk.paywall.archive.models

import com.superwall.sdk.storage.toMD5

sealed interface ArchivePart {
    val url: String
    val mimeType: String
    val content: ByteArray
    val contentId: String

    fun getSizeInMB(): String {
        val sizeInMB = content.size.toDouble() / (1024 * 1024)
        return String.format("%.2f MB", sizeInMB)
    }

    fun getSizeInMbDouble(): Double = content.size.toDouble() / (1024 * 1024)

    data class Resource(
        override val url: String,
        override val mimeType: String,
        override val content: ByteArray,
    ) : ArchivePart {
        override val contentId: String = "${url.toMD5()}"
    }

    data class Document(
        override val url: String,
        override val mimeType: String,
        override val content: ByteArray,
    ) : ArchivePart {
        override val contentId: String = ArchiveKeys.ContentId.MAIN_DOCUMENT.key
    }
}
