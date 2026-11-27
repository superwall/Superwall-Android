package com.superwall.sdk.network.signing

import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvent
import com.superwall.sdk.network.NetworkError
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * What a signed endpoint's response must be about, apart from the API key and nonce, which come
 * from the request as sent. For entitlements, [subject] is the user ID in the path and [deviceId]
 * its `deviceId` query value; for redemption, [subject] is the request body's hash. Both are empty
 * for config.
 */
internal data class SignedResponse(
    val endpoint: ResponseVerifier.Endpoint,
    val subject: String,
    val deviceId: String,
) {
    /** Whether the request sends a nonce: every signed endpoint but config, which is cached. */
    val takesNonce: Boolean
        get() = endpoint != ResponseVerifier.Endpoint.STATIC_CONFIG

    companion object {
        val STATIC_CONFIG = SignedResponse(ResponseVerifier.Endpoint.STATIC_CONFIG, "", "")

        /**
         * A redemption response, bound to the SHA-256 (lowercase hex) of [requestBody], the exact
         * bytes sent: a request changed on the way (another user, device or code) won't verify.
         */
        fun forRequestBody(
            endpoint: ResponseVerifier.Endpoint,
            requestBody: ByteArray,
        ): SignedResponse =
            SignedResponse(
                endpoint,
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(requestBody)
                    .joinToString("") { "%02x".format(it) },
                "",
            )
    }
}

/**
 * Ties [ResponseVerifier] to the SDK's requests. Every signed response is checked. By default a
 * failure is only reported, and the response is used as before; with
 * `SuperwallOptions.shouldEnforceResponseSignatures` a response that isn't verified, an unsigned
 * one included, fails like a network error.
 *
 * Once roots are embedded, an unsigned response is reported too, so a stripped header shows up
 * before apps enforce. Each endpoint and reason is reported once per SDK session.
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
        val reportUnsigned = verifier.hasRoots
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
        return when (val decision = decide(result, enforce, reportUnsigned)) {
            Decision.Accept -> null
            is Decision.Report -> {
                reportOnce(ResponseSignatureFailure(signed.endpoint, decision.reason, isEnforced = false))
                null
            }
            is Decision.Reject -> {
                reportOnce(ResponseSignatureFailure(signed.endpoint, decision.reason, isEnforced = true))
                NetworkError.InvalidResponseSignature(decision.reason)
            }
        }
    }

    /** What's been reported this session: config is fetched often, and one report says enough. */
    private val reported = ConcurrentHashMap.newKeySet<ResponseSignatureFailure>()

    private fun reportOnce(failure: ResponseSignatureFailure) {
        if (reported.add(failure)) report(failure)
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

        /**
         * @param reportUnsigned whether an unsigned response is worth reporting when not enforcing:
         * only once roots are embedded, since until then the backend doesn't sign at all.
         */
        fun decide(
            result: ResponseVerifier.Result,
            enforce: Boolean,
            reportUnsigned: Boolean = false,
        ): Decision =
            when (result) {
                ResponseVerifier.Result.Verified -> Decision.Accept
                ResponseVerifier.Result.Unsigned ->
                    when {
                        enforce -> Decision.Reject("unsigned")
                        reportUnsigned -> Decision.Report("unsigned")
                        else -> Decision.Accept
                    }
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
