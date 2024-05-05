package com.superwall.sdk.webarchive.archive

interface ArchiveEncoder {

    fun encode(content: ByteArray): String

    fun decode(content: ByteArray): ByteArray

    fun decodeDefault(string: String): ByteArray
}