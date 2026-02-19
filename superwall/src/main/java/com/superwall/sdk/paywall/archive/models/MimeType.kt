package com.superwall.sdk.paywall.archive.models

data class MimeType(
    val type: String,
    val subtype: String,
) {
    companion object {
        fun fromString(mimeType: String): MimeType {
            val parts = mimeType.split("/")
            return MimeType(parts[0], parts[1])
        }

        val HTML = MimeType("text", "html")
        val FAVICON = MimeType("image", "x-icon")
    }

    override fun toString(): String = "$type/$subtype"

    override fun equals(other: Any?): Boolean {
        when (other) {
            is MimeType -> return type == other.type && subtype == other.subtype
            is String -> return toString() == other
            else -> return super.equals(other)
        }
    }
}
