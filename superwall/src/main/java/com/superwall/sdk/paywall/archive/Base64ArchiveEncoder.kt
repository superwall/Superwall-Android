package com.superwall.sdk.paywall.archive

import android.util.Base64
import android.util.Base64OutputStream
import java.io.OutputStream

/**
 * Base64 implementation of archive encoder
 * with specific encoding width for compatibility with
 * chrome's export format and email clients
 */

class StreamingBase64ArchiveEncoder {
    fun streamEncode(
        input: ByteArray,
        out: OutputStream,
    ) {
        // CRLF line-folds *and* keep underlying stream open
        val FLAGS = Base64.CRLF or Base64.NO_CLOSE

        // The flag prevents FileOutputStream from being closed prematurely
        Base64OutputStream(out, FLAGS).use { enc ->
            enc.write(input) // enc.flush() happens inside use{} automatically
        }
    }

    fun decodeDefault(encoded: String): ByteArray =
        try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (t: Throwable) {
            ByteArray(0)
        }
}

class Base64ArchiveEncoder : ArchiveEncoder {
    override fun encode(content: ByteArray): String {
        return if (content.size > 1) {
            return try {
                return Base64.encodeToString(content, Base64.CRLF)
            } catch (e: Throwable) {
                "ICAgIA=="
            }
        } else {
            "ICAgIA=="
        }
    }

    // Decodes default B64
    override fun decodeDefault(string: String): ByteArray = Base64.decode(string, Base64.DEFAULT)

    override fun decode(content: ByteArray): ByteArray =
        Base64.decode(
            content,
            Base64.CRLF,
        )
}
