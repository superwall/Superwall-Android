package com.superwall.sdk.network
//
//import EventsRequest
//import EventsResponse
//import kotlinx.serialization.Serializable
//import kotlinx.coroutines.*
//import kotlinx.serialization.encodeToString
//import kotlinx.serialization.json.Json
//import java.net.URL
//import java.net.HttpURLConnection
//import java.util.*
//
//data class Endpoint<Response : Serializable>(
//    val components: Components? = null,
//    val url: URL? = null,
//    var method: HttpMethod = HttpMethod.GET,
//    var requestId: String = UUID.randomUUID().toString(),
//    var isForDebugging: Boolean = false,
//    val factory: ApiFactory
//) {
//    enum class HttpMethod(val method: String) {
//        GET("GET"),
//        POST("POST")
//    }
//
//    data class Components(
//        var scheme: String? = Api.scheme,
//        val host: String? = null,
//        val path: String,
//        var queryItems: List<URLQueryItem>? = null,
//        var bodyData: ByteArray? = null
//    )
//    suspend fun makeRequest(): HttpURLConnection? = coroutineScope {
//        val url: URL
//
//        if (components != null) {
//            val query = components.queryItems?.joinToString("&") { "${it.name}=${it.value}" }
//            val urlString = "${components.scheme}://${components.host}${components.path}?${query ?: ""}"
//            url = URL(urlString)
//        } else if (this@Endpoint.url != null) {
//            url = this@Endpoint.url!!
//        } else {
//            return@coroutineScope null
//        }
//
//        val connection = url.openConnection() as HttpURLConnection
//        connection.requestMethod = method.method
//        connection.doOutput = true
//        connection.doInput = true
//
//        if (components?.bodyData != null) {
//            val outputStream = connection.outputStream
//            outputStream.write(components.bodyData)
//            outputStream.close()
//        }
//
//        val headers = factory.makeHeaders(
//            connection = connection,
//            isForDebugging = isForDebugging,
//            requestId = requestId
//        )
//
//        headers.forEach { header ->
//            connection.setRequestProperty(header.key, header.value)
//        }
//
//        connection
//    }
//
//
//companion object {
//    fun events(
//        eventsRequest: EventsRequest,
//        factory: ApiFactory
//    ) : Endpoint<EventsResponse> {
//        val json = Json { encodeDefaults = true }
//        val bodyData = json.encodeToString(eventsRequest).toByteArray()
//        val collectorHost = factory.api.collector.host
//
//        return Endpoint(
//            components = Components(
//                host = collectorHost,
//                path = Api.version1 + "events",
//                bodyData = bodyData
//            ),
//            method = HttpMethod.POST,
//            factory = factory
//        )
//    }
//}
//}
