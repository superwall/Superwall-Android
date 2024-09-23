package com.superwall.sdk.network

import kotlinx.serialization.Serializable
import java.net.URL
import java.util.*

data class URLQueryItem(
    val name: String,
    val value: String,
)

class NetworkRequestData<Response>(
    val components: Components? = null,
    val url: URL? = null,
    var method: HttpMethod = HttpMethod.GET,
    var requestId: String = UUID.randomUUID().toString(),
    var isForDebugging: Boolean = false,
    val factory: suspend (isForDebugging: Boolean, requestId: String) -> Map<String, String>,
) where Response : @Serializable Any {
    enum class HttpMethod(
        val method: String,
    ) {
        GET("GET"),
        POST("POST"),
    }

    data class Components(
        var scheme: String? = Api.scheme,
        val host: String? = null,
        val path: String,
        var queryItems: List<URLQueryItem>? = null,
        var bodyData: ByteArray? = null,
    )
}
