package com.superwall.sdk.paywall.archive

interface ArchiveEncoder {
    fun encode(content: ByteArray): String

    fun decode(content: ByteArray): ByteArray

    fun decodeDefault(string: String): ByteArray
}
