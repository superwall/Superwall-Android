package com.superwall.sdk.paywall.archive.models

enum class ArchiveKeys(
    val key: String,
) {
    CONTENT_TYPE("Content-Type"),
    CONTENT_ID("Content-Id"),
    CONTENT_LOCATION("Content-Location"),
    CONTENT_TRANSFER_ENCODING("Content-Transfer-Encoding"),
    ;

    override fun toString() = key

    enum class TransferEncoding(
        val key: String,
    ) {
        QUOTED_PRINTABLE("quoted-printable"),
        BASE64("base64"),
        ;

        override fun toString() = key
    }

    enum class ContentId(
        val key: String,
    ) {
        MAIN_DOCUMENT("<mainDocument>"),
        ;

        override fun toString() = key
    }
}
