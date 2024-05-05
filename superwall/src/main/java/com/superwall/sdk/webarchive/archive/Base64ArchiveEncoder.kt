package com.superwall.sdk.webarchive.archive

import android.util.Base64

/**
 * Base64 implementation of archive encoder
 * with specific encoding width for compatibility with
 * chrome's export format and email clients
 */
class Base64ArchiveEncoder : ArchiveEncoder {
    override fun encode(content: ByteArray): String {
        return Base64.encodeToString(content, Base64.NO_WRAP, 76, Base64.DEFAULT)
    }

    /*Decodes default B64*/
    override fun decodeDefault(string: String): ByteArray {
        return Base64.decode(string, Base64.DEFAULT)
    }

    override fun decode(content: ByteArray): ByteArray {
        return Base64.decode(
            content, Base64.NO_WRAP,
            76,
            Base64.DEFAULT
        )
    }

}