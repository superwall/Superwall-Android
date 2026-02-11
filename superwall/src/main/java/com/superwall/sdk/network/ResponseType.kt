package com.superwall.sdk.network

sealed class ResponseType {
    data class Text(
        val string: ByteArray,
    ) : ResponseType()

    data class Binary(
        val bytes: ByteArray,
    ) : ResponseType()
}
