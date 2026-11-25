package com.superwall.sdk.network.signing

import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvent
import com.superwall.sdk.network.NetworkError
import java.security.SecureRandom

/**
 * What a signed endpoint's response must be about, apart from the API key and nonce, which come
 * from the request as sent. [subject] is the user ID in the entitlements path; [deviceId] its
 * `deviceId` query value. Both are empty for config.
 */
internal data class SignedResponse(
    val endpoint: ResponseVerifier.Endpoint,
    val subject: String,
    val deviceId: String,
) {
    companion object {
        val STATIC_CONFIG = SignedResponse(ResponseVerifier.Endpoint.STATIC_CONFIG, "", "")
    }
}

/**
 * Ties [ResponseVerifier] to the SDK's requests. Every signed response is checked. By default a
 * failure is only reported, and the response is used as before; with
 * `SuperwallOptions.shouldEnforceResponseSignatures` a response that isn't verified, an unsigned
 * one included, fails like a network error.
 */
internal class ResponseSignatureCheck(
    private val isEnforced: () -> Boolean,
    private val report: (ResponseSignatureFailure) -> Unit,
    private val verifier: ResponseVerifier = ResponseVerifier(ResponseSigningRoots.all),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    sealed interface Decision {
        /** Use the response, saying nothing. */
        data object Accept : Decision

        /** Use the response, and report why it didn't verify. */
        data class Report(
            val reason: String,
        ) : Decision

        /** Fail the request like a network error, and report why. */
        data class Reject(
            val reason: String,
        ) : Decision
    }

    /**
     * Checks a response. Returns the error to fail the request with, or `null` to use the
     * response.
     *
     * @param apiKey the public API key the request carried (config's `pk`, entitlements' bearer).
     * @param signatureHeader the response's `X-Superwall-Signature`; several joined with ", ".
     * @param body the response body exactly as received.
     */
    fun check(
        signed: SignedResponse,
        apiKey: String?,
        nonce: String,
        signatureHeader: String?,
        body: ByteArray,
    ): NetworkError? {
        val enforce = isEnforced()
        val result =
            if (apiKey.isNullOrEmpty()) {
                ResponseVerifier.Result.Failed(ResponseVerifier.Failure.MALFORMED_HEADER)
            } else {
                verifier.verify(
                    header = signatureHeader,
                    context =
                        ResponseVerifier.Context(
                            endpoint = signed.endpoint,
                            apiKey = apiKey,
                            subject = signed.subject,
                            deviceId = signed.deviceId,
                            nonce = nonce,
                        ),
                    body = body,
                    nowSeconds = nowSeconds(),
                )
            }
        return when (val decision = decide(result, enforce)) {
            Decision.Accept -> null
            is Decision.Report -> {
                report(ResponseSignatureFailure(signed.endpoint, decision.reason, isEnforced = false))
                null
            }
            is Decision.Reject -> {
                report(ResponseSignatureFailure(signed.endpoint, decision.reason, isEnforced = true))
                NetworkError.InvalidResponseSignature(decision.reason)
            }
        }
    }

    companion object {
        private val random = SecureRandom()

        /** 16 random bytes, base64url without padding (22 characters). */
        fun makeNonce(): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return StrictBase64
                .encode(bytes)
                .replace('+', '-')
                .replace('/', '_')
                .trimEnd('=')
        }

        /** The token of an `Authorization: Bearer <token>` header, or `null`. */
        fun bearerToken(authorization: String?): String? =
            authorization
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")

        fun decide(
            result: ResponseVerifier.Result,
            enforce: Boolean,
        ): Decision =
            when (result) {
                ResponseVerifier.Result.Verified -> Decision.Accept
                // Unsigned is normal while the backend doesn't sign; it only matters when enforcing.
                ResponseVerifier.Result.Unsigned -> if (enforce) Decision.Reject("unsigned") else Decision.Accept
                is ResponseVerifier.Result.Failed ->
                    if (enforce) {
                        Decision.Reject(result.reason.wireName)
                    } else {
                        Decision.Report(result.reason.wireName)
                    }
            }
    }
}

/**
 * Sent when a signed response doesn't verify. Private: it never reaches the delegate and can't
 * trigger a paywall. It carries the reason only, never the response.
 */
internal data class ResponseSignatureFailure(
    val endpoint: ResponseVerifier.Endpoint,
    val reason: String,
    val isEnforced: Boolean,
) : PrivateSuperwallEvent {
    override val rawName: String
        get() = NAME

    override val audienceFilterParams: Map<String, Any>
        get() =
            mapOf(
                "endpoint" to endpoint.wireName,
                "reason" to reason,
                "is_enforced" to isEnforced,
            )

    companion object {
        const val NAME = "response_signature_failed"
    }
}
