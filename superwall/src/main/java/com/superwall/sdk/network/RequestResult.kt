package com.superwall.sdk.network

data class RequestResult(
    val requestId: String,
    val responseCode: Int,
    val responseMessage: String,
    val duration: Double,
    /** The request's headers, as sent. */
    val headers: Map<String, String>,
    /** The response's `X-Superwall-Signature`, several joined with ", " (which then don't parse). */
    val signatureHeader: String? = null,
    /** The response body exactly as received; `responseMessage` is it decoded as UTF-8. */
    val body: ByteArray? = null,
)

fun RequestResult.authHeader(): String = headers["Authorization"] ?: ""
