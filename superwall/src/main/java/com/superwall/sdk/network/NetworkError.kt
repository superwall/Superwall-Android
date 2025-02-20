package com.superwall.sdk.network

sealed class NetworkError(
    message: String,
    cause: Throwable? = null,
) : Throwable(message, cause) {
    data class Unknown(
        override val cause: Throwable? = null,
    ) : NetworkError("An unknown error occurred.", cause)

    class NotAuthenticated : NetworkError("Unauthorized.")

    class Decoding(
        cause: Throwable? = null,
    ) : NetworkError("Decoding error ${cause?.message}", cause)

    class NotFound : NetworkError("Not found.")

    class InvalidUrl : NetworkError("URL invalid.")
}
