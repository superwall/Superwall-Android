package com.superwall.sdk.network

sealed class NetworkError(
    message: String,
    cause: Throwable? = null,
) : Throwable(message, cause) {
    data class Unknown(
        override val cause: Throwable? = null,
    ) : NetworkError(cause?.localizedMessage ?: cause?.message ?: "Unknown error occurred.", cause)

    class NotAuthenticated : NetworkError("Unauthorized.")

    object Timeout : NetworkError("Timeout occured.")

    class Decoding(
        cause: Throwable? = null,
    ) : NetworkError("Decoding error ${cause?.message}", cause)

    class NotFound : NetworkError("Not found.")

    class InvalidUrl : NetworkError("URL invalid.")

    /**
     * The response's `X-Superwall-Signature` didn't verify, while
     * `SuperwallOptions.shouldEnforceResponseSignatures` is on. [reason] says why.
     */
    class InvalidResponseSignature(
        val reason: String,
    ) : NetworkError("The response signature didn't verify: $reason.")
}
