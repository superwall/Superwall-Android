package com.superwall.sdk.network.signing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies `X-Superwall-Signature` on SDK responses. The format and the shared test vectors live in
 * superwall/paywall-next `packages/response-signing`; iOS implements the same steps.
 *
 * The response is signed by a short-lived signing key, which an embedded root authorises with a
 * raw key statement (`kind=raw`, the only kind): a fixed layout the root signs directly. There is
 * no X.509 and no intermediate, so there's nothing to parse but that layout.
 */
internal class ResponseVerifier(
    private val roots: List<TrustedRoot>,
) {
    /** Whether any root is embedded. Without one nothing can verify. */
    val hasRoots: Boolean
        get() = roots.isNotEmpty()

    /** A root the SDK trusts: its 65-byte uncompressed P-256 public key (see [ResponseSigningRoots]). */
    class TrustedRoot(
        val publicKey: ByteArray,
    ) {
        internal val keyId: ByteArray = sha256(publicKey)
        internal val javaPublicKey: PublicKey? = p256PublicKey(publicKey)
    }

    enum class Endpoint(
        val wireName: String,
    ) {
        STATIC_CONFIG("static_config"),
        ENTITLEMENTS("entitlements"),

        /** `POST /redeem`: its subject is the request body's hash (see [SignedResponse.forRequestBody]). */
        REDEEM("redeem"),

        /** `POST /checkout/session/poll-redemption-result`, bound like [REDEEM]. */
        POLL_REDEMPTION_RESULT("poll_redemption_result"),
    }

    /**
     * What the response must be about, from the request this SDK sent:
     * - [apiKey]: the public API key the request used.
     * - [subject]: entitlements only, the user ID in the path (not URL-encoded); empty for config.
     * - [deviceId]: entitlements only, the `deviceId` query value; empty for config.
     * - [nonce]: the `X-Superwall-Nonce` this request sent; empty for config.
     *
     * Callers that enforce must also treat [Result.Unsigned] as a failure, always send a fresh
     * nonce for entitlements, and reject a response with more than one signature header.
     */
    data class Context(
        val endpoint: Endpoint,
        val apiKey: String,
        val subject: String,
        val deviceId: String,
        val nonce: String,
    )

    enum class Failure(
        val wireName: String,
    ) {
        MALFORMED_HEADER("malformed_header"),
        UNSUPPORTED_VERSION("unsupported_version"),
        MALFORMED_CREDENTIAL("malformed_credential"),
        UNKNOWN_ROOT("unknown_root"),
        INVALID_CREDENTIAL_SIGNATURE("invalid_credential_signature"),
        CREDENTIAL_NOT_VALID_AT_SIGNING_TIME("credential_not_valid_at_signing_time"),
        SIGNED_IN_FUTURE("signed_in_future"),
        CREDENTIAL_EXPIRED("credential_expired"),
        INVALID_SIGNATURE("invalid_signature"),
    }

    sealed class Result {
        data object Verified : Result()

        data object Unsigned : Result()

        data class Failed(
            val reason: Failure,
        ) : Result()
    }

    private class VerifiedKey(
        val publicKey: PublicKey,
        val notBefore: Long,
        val notAfter: Long,
    )

    private sealed interface CredentialCheck {
        class Accepted(
            val key: VerifiedKey,
        ) : CredentialCheck

        class Rejected(
            val reason: Failure,
        ) : CredentialCheck
    }

    /** Verified credentials, keyed by their SHA-256, so a reused credential is checked once. */
    private val credentialCache =
        object : LinkedHashMap<String, VerifiedKey>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VerifiedKey>?) = size > 8
        }

    /**
     * @param header the response's `X-Superwall-Signature`, or `null`.
     * @param body the response body exactly as received, after decompression.
     * @param nowSeconds the device's current Unix time.
     */
    fun verify(
        header: String?,
        context: Context,
        body: ByteArray,
        nowSeconds: Long,
    ): Result {
        if (header == null) return Result.Unsigned
        val envelope = SignatureEnvelope.parse(header) ?: return Result.Failed(Failure.MALFORMED_HEADER)
        if (envelope.version != SignatureEnvelope.VERSION) {
            return Result.Failed(Failure.UNSUPPORTED_VERSION)
        }

        val credentialHash = sha256(envelope.credential)
        val cacheKey = StrictBase64.encode(credentialHash)
        val key =
            synchronized(credentialCache) { credentialCache[cacheKey] }
                ?: when (val check = verifyRawCredential(envelope.credential)) {
                    is CredentialCheck.Rejected -> return Result.Failed(check.reason)
                    is CredentialCheck.Accepted -> check.key
                }

        if (envelope.signedAt < key.notBefore || envelope.signedAt > key.notAfter) {
            return Result.Failed(Failure.CREDENTIAL_NOT_VALID_AT_SIGNING_TIME)
        }
        if (envelope.signedAt > nowSeconds + CLOCK_TOLERANCE_SECONDS) {
            return Result.Failed(Failure.SIGNED_IN_FUTURE)
        }
        // Bounds what a leaked signing key, or a replayed config, is good for. A subtraction, so a
        // far-future `notAfter` can't overflow.
        if (nowSeconds - CLOCK_TOLERANCE_SECONDS > key.notAfter) {
            return Result.Failed(Failure.CREDENTIAL_EXPIRED)
        }
        // Only cache a credential once it's been used for a valid signing time.
        synchronized(credentialCache) { credentialCache[cacheKey] = key }

        val message = signedMessage(credentialHash, envelope.signedAt, context, body)
        return if (verifyEcdsa(key.publicKey, envelope.signature, message)) {
            Result.Verified
        } else {
            Result.Failed(Failure.INVALID_SIGNATURE)
        }
    }

    // region Raw key statements

    private fun verifyRawCredential(bytes: ByteArray): CredentialCheck {
        // u8 version | u8 alg | [32] rootKeyId | i64 notBefore | i64 notAfter | [65] key
        // u16 sigLen | [sigLen] root signature over KEY_STATEMENT_PREFIX || statement
        if (bytes.size < STATEMENT_LENGTH + 2) return CredentialCheck.Rejected(Failure.MALFORMED_CREDENTIAL)
        if (bytes[0].toInt() != 1 || bytes[1].toInt() != 1) return CredentialCheck.Rejected(Failure.MALFORMED_CREDENTIAL)
        val buffer = ByteBuffer.wrap(bytes)
        val signatureLength = buffer.getShort(STATEMENT_LENGTH).toInt() and 0xffff
        if (bytes.size != STATEMENT_LENGTH + 2 + signatureLength) return CredentialCheck.Rejected(Failure.MALFORMED_CREDENTIAL)

        val rootKeyId = bytes.copyOfRange(2, 34)
        val root =
            roots.firstOrNull { MessageDigest.isEqual(it.keyId, rootKeyId) }
                ?: return CredentialCheck.Rejected(Failure.UNKNOWN_ROOT)
        val rootKey = root.javaPublicKey ?: return CredentialCheck.Rejected(Failure.UNKNOWN_ROOT)
        val statement = bytes.copyOfRange(0, STATEMENT_LENGTH)
        val rootSignature = bytes.copyOfRange(STATEMENT_LENGTH + 2, bytes.size)
        if (!verifyEcdsa(rootKey, rootSignature, KEY_STATEMENT_PREFIX + statement)) {
            return CredentialCheck.Rejected(Failure.INVALID_CREDENTIAL_SIGNATURE)
        }
        // What the root signed must still make sense: a real validity no longer than the cap, and
        // a signing key that is a point on P-256.
        val notBefore = buffer.getLong(34)
        val notAfter = buffer.getLong(42)
        val lifetime = runCatching { Math.subtractExact(notAfter, notBefore) }.getOrNull()
        if (lifetime == null || lifetime < 0 || lifetime > MAX_SIGNING_KEY_LIFETIME_SECONDS) {
            return CredentialCheck.Rejected(Failure.MALFORMED_CREDENTIAL)
        }
        val signingKey =
            p256PublicKey(bytes.copyOfRange(50, 115))
                ?: return CredentialCheck.Rejected(Failure.MALFORMED_CREDENTIAL)
        return CredentialCheck.Accepted(VerifiedKey(signingKey, notBefore, notAfter))
    }

    // endregion

    companion object {
        /**
         * How far the device clock may be from the server's: a signature dated further ahead
         * than this is rejected, and so is any response once the clock is this far past the
         * signing key's expiry. Generous, because device clocks are often wrong; entitlements get
         * their freshness from the nonce, not the clock.
         */
        const val CLOCK_TOLERANCE_SECONDS = 24 * 60 * 60L

        /**
         * The longest validity a key statement may give a signing key (7 days issued, plus a day).
         * Longer ones are refused, so whoever can make the root sign can't mint long-lived keys.
         */
        const val MAX_SIGNING_KEY_LIFETIME_SECONDS = 8 * 24 * 60 * 60L

        private const val STATEMENT_LENGTH = 1 + 1 + 32 + 8 + 8 + 65

        private val KEY_STATEMENT_PREFIX = "superwall-signing-key-v1\u0000".toByteArray(Charsets.UTF_8)
        private val RESPONSE_PREFIX = "superwall-response-v1\u0000".toByteArray(Charsets.UTF_8)

        /** DER prefix of a P-256 SubjectPublicKeyInfo; the 65-byte uncompressed point follows it. */
        private val P256_SPKI_PREFIX =
            byteArrayOf(
                0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
                0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01,
                0x07, 0x03, 0x42, 0x00,
            )

        internal fun signedMessage(
            credentialHash: ByteArray,
            signedAt: Long,
            context: Context,
            body: ByteArray,
        ): ByteArray {
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            out.write(RESPONSE_PREFIX)
            for (field in listOf(
                credentialHash,
                signedAt.toString().toByteArray(Charsets.UTF_8),
                context.endpoint.wireName.toByteArray(Charsets.UTF_8),
                context.apiKey.toByteArray(Charsets.UTF_8),
                context.subject.toByteArray(Charsets.UTF_8),
                context.deviceId.toByteArray(Charsets.UTF_8),
                context.nonce.toByteArray(Charsets.UTF_8),
                sha256(body),
            )) {
                out.writeInt(field.size)
                out.write(field)
            }
            return bytes.toByteArray()
        }

        private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

        /**
         * Whether `point` is a 65-byte uncompressed point on P-256: y² = x³ − 3x + b (mod p). Checked
         * here because the desktop JDK's `KeyFactory` doesn't check it and Android's does, and
         * every platform must agree.
         */
        internal fun isP256Point(point: ByteArray): Boolean {
            if (point.size != 65 || point[0].toInt() != 0x04) return false
            val x = BigInteger(1, point.copyOfRange(1, 33))
            val y = BigInteger(1, point.copyOfRange(33, 65))
            if (x >= P256_P || y >= P256_P) return false
            val left = y.multiply(y).mod(P256_P)
            val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(P256_B).mod(P256_P)
            return left == right
        }

        /** The key for a 65-byte uncompressed P-256 point, or `null` for anything else. */
        internal fun p256PublicKey(point: ByteArray): PublicKey? {
            if (!isP256Point(point)) return null
            // `X509EncodedKeySpec` is Java's name for a SubjectPublicKeyInfo; no certificate involved.
            return try {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(P256_SPKI_PREFIX + point))
            } catch (error: GeneralSecurityException) {
                null
            }
        }

        /** ECDSA P-256 / SHA-256 with a DER signature. Malformed input is `false`. */
        internal fun verifyEcdsa(
            publicKey: PublicKey,
            derSignature: ByteArray,
            message: ByteArray,
        ): Boolean =
            try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(message)
                    verify(derSignature)
                }
            } catch (error: GeneralSecurityException) {
                // Includes `SignatureException` for a signature that isn't valid DER.
                false
            } catch (error: IllegalArgumentException) {
                false
            }

        internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
