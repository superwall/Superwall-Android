package com.superwall.sdk.network

class RequestResult(
    val requestId: String,
    val responseCode: Int,
    val responseMessage: String,
    val duration: Double,
    val headers: Map<String, String>,
)

fun RequestResult.authHeader(): String = headers["Authorization"] ?: ""
