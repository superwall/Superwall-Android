package com.superwall.sdk.paywall.archive

import android.util.Base64
import android.util.Log

/**
 * Base64 implementation of archive encoder
 * with specific encoding width for compatibility with
 * chrome's export format and email clients
 */
class Base64ArchiveEncoder : ArchiveEncoder {
    override fun encode(content: ByteArray): String {
        return if (content.size > 1) {
            return try {
                return Base64.encodeToString(content, Base64.CRLF)
            } catch (e: Throwable) {
                e.printStackTrace()
                Log.e("Encoding broken", "Cant encode ${content.toString(Charsets.UTF_8)}")
                "ICAgIA=="
            }
        } else {
            "ICAgIA=="
        }
    }

    // Decodes default B64
    override fun decodeDefault(string: String): ByteArray = Base64.decode(string, Base64.DEFAULT)

    override fun decode(content: ByteArray): ByteArray {
        if (content.size < 78) {
            Log.e(
                "ArchiveClient",
                "Content size is ${content.size} - ${content.toString(Charsets.UTF_8)}",
            )
        }

        return Base64.decode(
            content,
            Base64.CRLF,
        )
    }
}
