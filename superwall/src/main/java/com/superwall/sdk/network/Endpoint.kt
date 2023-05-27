package com.superwall.sdk.network

import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.SerializableEntity
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.assignment.ConfirmedAssignmentResponse
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.models.events.EventsResponse
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallRequestBody
import com.superwall.sdk.models.postback.PostBackResponse
import com.superwall.sdk.models.postback.Postback
import kotlinx.serialization.Serializable
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.net.URL
import java.net.HttpURLConnection
import java.util.*

data class URLQueryItem(val name: String, val value: String)


data class Endpoint<Response : SerializableEntity>(
    val components: Components? = null,
    val url: URL? = null,
    var method: HttpMethod = HttpMethod.GET,
    var requestId: String = UUID.randomUUID().toString(),
    var isForDebugging: Boolean = false,
    val factory: ApiFactory
) {
    enum class HttpMethod(val method: String) {
        GET("GET"),
        POST("POST")
    }

    data class Components(
        var scheme: String? = Api.scheme,
        val host: String? = null,
        val path: String,
        var queryItems: List<URLQueryItem>? = null,
        var bodyData: ByteArray? = null
    )
    suspend fun makeRequest(): HttpURLConnection? = coroutineScope {
        val url: URL

        if (components != null) {
            val query = components.queryItems?.joinToString("&") { "${it.name}=${it.value}" }
            val urlString = "${components.scheme}://${components.host}${components.path}?${query ?: ""}"
            url = URL(urlString)
        } else if (this@Endpoint.url != null) {
            url = this@Endpoint.url!!
        } else {
            return@coroutineScope null
        }

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method.method
        connection.doOutput = method.method == HttpMethod.POST.method
//        connection.doInput = true

        if (components?.bodyData != null) {
            val outputStream = connection.outputStream
            outputStream.write(components.bodyData)
            outputStream.close()
        }

        val headers = factory.makeHeaders(
            fromRequest = connection,
            isForDebugging = isForDebugging,
            requestId = requestId
        )

        headers.forEach { header ->
            connection.setRequestProperty(header.key, header.value)
        }

        return@coroutineScope connection
    }


    companion object {
        fun events(
            eventsRequest: EventsRequest,
            factory: ApiFactory
        ) : Endpoint<EventsResponse> {
            val json = Json { encodeDefaults = true }
            val bodyData = json.encodeToString(eventsRequest).toByteArray()
            val collectorHost = factory.api.collector.host

            return Endpoint<EventsResponse>(
                components = Components(
                    host = collectorHost,
                    path = Api.version1 + "events",
                    bodyData = bodyData
                ),
                method = HttpMethod.POST,
                factory = factory
            )
        }

        fun config(
            requestId: String,
            factory: ApiFactory
        ): Endpoint<Config> {
            val queryItems = listOf(URLQueryItem("pk", factory.storage.apiKey))
            val baseHost = factory.api.base.host

            return Endpoint(
                components = Components(
                    host = baseHost,
                    path = Api.version1 + "static_config",
                    queryItems = queryItems
                ),
                method = HttpMethod.GET,
                requestId = requestId,
                factory = factory
            )
        }

        fun assignments(factory: ApiFactory): Endpoint<ConfirmedAssignmentResponse> {
            val baseHost = factory.api.base.host

            return Endpoint(
                components = Components(
                    host = baseHost,
                    path = Api.version1 + "assignments"
                ),
                method = HttpMethod.GET,
                factory = factory
            )
        }

        fun confirmAssignments(
            confirmableAssignments: AssignmentPostback,
            factory: ApiFactory
        ): Endpoint<ConfirmedAssignmentResponse> {
            val json = Json {
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
            val bodyData = json.encodeToString(confirmableAssignments).toByteArray()
            val baseHost = factory.api.base.host

            return Endpoint(
                components = Components(
                    host = baseHost,
                    path = Api.version1 + "confirm_assignments",
                    bodyData = bodyData
                ),
                method = HttpMethod.POST,
                factory = factory
            )
        }

        fun postback(
            postback: Postback,
            factory: ApiFactory
        ): Endpoint<PostBackResponse> {
            val json = Json {
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
            val bodyData = json.encodeToString(postback).toByteArray()
            val collectorHost = factory.api.collector.host

            return Endpoint(
                components = Components(
                    host = collectorHost,
                    path = Api.version1 + "postback",
                    bodyData = bodyData
                ),
                method = HttpMethod.POST,
                factory = factory
            )
        }

//        fun paywall(
//            identifier: String? = null,
//            event: EventData? = null,
//            factory: ApiFactory
//        ): Endpoint<Paywall> {
//            var bodyData: String? = null
//
//            return when {
//                identifier != null -> {
//                    paywallByIdentifier(identifier, factory)
//                }
//                event != null -> {
//                    val bodyDict = mapOf("event" to event.jsonData)
//                    val json = Json { encodeDefaults = true }
//                    bodyData = json.encodeToString(bodyDict).toSnakeCase()
//                    createEndpointWithBodyData(bodyData, factory)
//                }
//                else -> {
//                    val body = PaywallRequestBody(appUserId = factory.identityManager.userId)
//                    val json = Json { encodeDefaults = true }
//                    bodyData = json.encodeToString(body).toSnakeCase()
//                    createEndpointWithBodyData(bodyData, factory)
//                }
//            }
//        }

        private fun createEndpointWithBodyData(bodyData: String?, factory: ApiFactory): Endpoint<Paywall> {
            val baseHost = factory.api.base.host

            var _bodyData: ByteArray? = null
            if (bodyData != null)  {
                _bodyData = bodyData.toByteArray()
            }

            return Endpoint(
                components = Components(
                    host = baseHost,
                    path = Api.version1 + "paywall",
                    bodyData = _bodyData
                ),
                method = HttpMethod.POST,
                factory = factory
            )
        }
//
//        private fun paywallByIdentifier(
//            identifier: String,
//            factory: ApiFactory
//        ): Endpoint<Paywall> {
//            var queryItems = mutableListOf(URLQueryItem("pk", factory.storage.apiKey))
//
//            factory.configManager.config?.let { config ->
//                when {
//                    config.locales.contains(factory.deviceHelper.locale) -> {
//                        val localeQuery = URLQueryItem(
//                            name = "locale",
//                            value = factory.deviceHelper.locale
//                        )
//                        queryItems.add(localeQuery)
//                    }
//                    else -> {
//                        val shortLocale = factory.deviceHelper.locale.split("_")[0]
//                        if (config.locales.contains(shortLocale)) {
//                            val localeQuery = URLQueryItem(
//                                name = "locale",
//                                value = shortLocale
//                            )
//                            queryItems.add(localeQuery)
//                        }
//                    }
//                }
//            }
//            val baseHost = factory.api.base.host
//
//            return Endpoint(
//                components = Components(
//                    host = baseHost,
//                    path = "${Api.version1}paywall/$identifier",
//                    queryItems = queryItems
//                ),
//                method = HttpMethod.GET,
//                factory = factory
//            )
//        }

    }
}
