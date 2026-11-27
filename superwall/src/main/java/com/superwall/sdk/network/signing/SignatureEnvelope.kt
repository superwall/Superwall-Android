package com.superwall.sdk.network.signing

/**
 * The parsed `X-Superwall-Signature` header:
 * `v=1, t=<unix seconds>, kind=raw, cred=:<base64>:, sig=:<base64>:`.
 *
 * `raw` is the only credential kind; anything else, `x509` included, is malformed.
 *
 * A subset of an RFC 8941 dictionary. Members may come in any order and unknown members are
 * ignored. The format is specified in superwall/paywall-next `packages/response-signing`.
 */
internal class SignatureEnvelope(
    /** A `Long`, so any 15-digit version is `unsupported_version` rather than malformed. */
    val version: Long,
    val signedAt: Long,
    val credential: ByteArray,
    val signature: ByteArray,
) {
    companion object {
        const val SIGNATURE_HEADER = "X-Superwall-Signature"
        const val NONCE_HEADER = "X-Superwall-Nonce"
        const val VERSION = 1L
        const val KIND_RAW = "raw"

        /**
         * Returns `null` for anything malformed. Only visible ASCII, spaces and tabs are allowed,
         * and members are trimmed of spaces and tabs only, so every platform splits the same way.
         */
        fun parse(value: String): SignatureEnvelope? {
            if (!value.all { it == '\t' || it in ' '..'~' }) return null
            val members = HashMap<String, String>()
            for (part in value.split(",")) {
                val member = part.trim { it == ' ' || it == '\t' }
                val equals = member.indexOf('=')
                if (equals <= 0) return null
                val key = member.substring(0, equals)
                if (members.containsKey(key)) return null
                members[key] = member.substring(equals + 1)
            }

            fun integer(key: String): Long? {
                val raw = members[key] ?: return null
                // Canonical decimal: 1–15 digits, no leading zeros.
                if (raw.isEmpty() || raw.length > 15 || !raw.all { it in '0'..'9' }) return null
                if (raw.length > 1 && raw[0] == '0') return null
                return raw.toLong()
            }

            fun bytes(key: String): ByteArray? {
                val raw = members[key] ?: return null
                if (raw.length < 2 || !raw.startsWith(":") || !raw.endsWith(":")) return null
                return StrictBase64.decode(raw.substring(1, raw.length - 1))
            }

            if (members["kind"] != KIND_RAW) return null
            val version = integer("v") ?: return null
            return SignatureEnvelope(
                version = version,
                signedAt = integer("t") ?: return null,
                credential = bytes("cred") ?: return null,
                signature = bytes("sig") ?: return null,
            )
        }
    }
}

/** Standard, padded, canonical base64. `java.util.Base64` needs API 26, so it's done by hand. */
internal object StrictBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        for (start in bytes.indices step 3) {
            val count = minOf(3, bytes.size - start)
            var chunk = 0
            for (offset in 0 until 3) {
                chunk = (chunk shl 8) or (if (offset < count) bytes[start + offset].toInt() and 0xff else 0)
            }
            for (index in 0 until 4) {
                out.append(if (index <= count) ALPHABET[(chunk shr (18 - 6 * index)) and 0x3f] else '=')
            }
        }
        return out.toString()
    }

    fun decode(value: String): ByteArray? {
        if (value.length % 4 != 0) return null
        val padding = value.length - value.trimEnd('=').length
        if (padding > 2) return null
        val out = ByteArray(value.length / 4 * 3 - padding)
        var outIndex = 0
        var index = 0
        while (index < value.length) {
            var chunk = 0
            for (offset in 0 until 4) {
                val char = value[index + offset]
                val digit =
                    if (char == '=') {
                        if (index + offset < value.length - padding) return null
                        0
                    } else {
                        ALPHABET.indexOf(char).takeIf { it >= 0 } ?: return null
                    }
                chunk = (chunk shl 6) or digit
            }
            for (shift in intArrayOf(16, 8, 0)) {
                if (outIndex < out.size) out[outIndex++] = (chunk shr shift).toByte()
            }
            index += 4
        }
        // Canonical: the unused bits of the last character are zero, so each byte string has one
        // encoding (`QR==` is refused; `QQ==` is the same byte).
        return out.takeIf { encode(it) == value }
    }
}
