package com.superwall.sdk.network

data class FileResponse(
    val content: ByteArray,
    val type: String?,
    val extras: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileResponse

        if (!content.contentEquals(other.content)) return false
        if (type != other.type) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + extras.hashCode()
        return result
    }
}
