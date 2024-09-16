package com.superwall.sdk.network.session

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.retrying
import com.superwall.sdk.network.NetworkRequestData
import com.superwall.sdk.network.NetworkRequestData.HttpMethod
import com.superwall.sdk.network.URLQueryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

abstract class NetworkService {
    abstract val customHttpUrlConnection: CustomHttpUrlConnection

    abstract suspend fun makeHeaders(
        isForDebugging: Boolean,
        requestId: String,
    ): Map<String, String>

    abstract val host: String
    abstract val version: String

    suspend inline fun <reified T> get(
        path: String,
        queryItems: List<URLQueryItem>? = null,
        isForDebugging: Boolean = false,
        requestId: String = UUID.randomUUID().toString(),
        retryCount: Int = 6,
    ): Either<T, NetworkError> where T : @Serializable Any =
        customHttpUrlConnection.request(
            makeRequest = {
                NetworkRequestData<T>(
                    isForDebugging = isForDebugging,
                    requestId = requestId,
                    components =
                        NetworkRequestData.Components(
                            host = host,
                            path = version + path,
                            queryItems = queryItems,
                        ),
                    method = NetworkRequestData.HttpMethod.GET,
                    factory = this::makeHeaders,
                ).makeRequest()
            },
            retryCount = retryCount,
        )

    suspend inline fun <reified T> post(
        path: String,
        isForDebugging: Boolean = false,
        body: ByteArray? = null,
        requestId: String = UUID.randomUUID().toString(),
        retryCount: Int = 6,
    ): Either<T, NetworkError> where T : @Serializable Any =
        customHttpUrlConnection.request<T>(
            makeRequest = {
                NetworkRequestData<T>(
                    isForDebugging = isForDebugging,
                    requestId = requestId,
                    components =
                        NetworkRequestData.Components(
                            host = host,
                            path = path,
                            bodyData = body,
                        ),
                    method = NetworkRequestData.HttpMethod.POST,
                    factory = this::makeHeaders,
                ).makeRequest()
            },
            retryCount = retryCount,
        )

    suspend fun <T : @Serializable Any> NetworkRequestData<T>.makeRequest(): HttpURLConnection? {
        val url: URL

        if (components != null) {
            val query = components.queryItems?.joinToString("&") { "${it.name}=${it.value}" }
            val urlString =
                "${components.scheme}://${components.host}${components.path}?${query ?: ""}"
            url = URL(urlString)
        } else if (this.url != null) {
            url = this.url!!
        } else {
            return null
        }

        val headers =
            makeHeaders(
                isForDebugging,
                requestId,
            )
        val connection = url.openConnection() as HttpURLConnection
        headers.forEach { header ->
            connection.setRequestProperty(header.key, header.value)
        }

        connection.doOutput = method.method == HttpMethod.POST.method
        if (components?.bodyData != null) {
            connection.doInput = true
        }

        if (components?.bodyData != null) {
            val outputStream = connection.outputStream
            outputStream.write(components.bodyData)
            outputStream.close()
        }

        connection.requestMethod = method.method

        return connection
    }
}

sealed class NetworkError(
    message: String,
    cause: Throwable? = null,
) : Throwable(message, cause) {
    class Unknown : NetworkError("An unknown error occurred.")

    class NotAuthenticated : NetworkError("Unauthorized.")

    class Decoding(
        cause: Throwable? = null,
    ) : NetworkError("Decoding error.", cause)

    class NotFound : NetworkError("Not found.")

    class InvalidUrl : NetworkError("URL invalid.")
}

class CustomHttpUrlConnection(
    val scope: CoroutineScope,
    val json: Json,
) {
    @Throws(NetworkError::class)
    suspend inline fun <reified Response : @Serializable Any> request(
        crossinline makeRequest: suspend () -> HttpURLConnection?,
        retryCount: Int,
        noinline isRetryingCallback: (suspend () -> Unit)? = null,
    ): Either<Response, NetworkError> {
        return retrying(
            coroutineContext = scope.coroutineContext,
            maxRetryCount = retryCount,
            isRetryingCallback = isRetryingCallback,
        ) {
            val request =
                try {
                    makeRequest() ?: return@retrying Either.Failure(NetworkError.Unknown())
                } catch (e: Throwable) {
                    return@retrying Either.Failure(if (e is NetworkError) e else NetworkError.Unknown())
                }

            val auth =
                request.getRequestProperty("Authorization") ?: throw NetworkError.NotAuthenticated()

            Logger.debug(
                LogLevel.debug,
                LogScope.network,
                "Request Started",
                mapOf(
                    "url" to (request.url?.toString() ?: "unknown"),
                ),
            )

            val startTime = System.currentTimeMillis()
            val responseCode: Int = request.responseCode

        var responseMessage: String? = null
        if (responseCode == HttpURLConnection.HTTP_OK) {
            responseMessage = request.inputStream.bufferedReader().use { it.readText() }
            request.disconnect()
        } else {
            Logger.debug(
                LogLevel.debug,
                LogScope.network,
                "!!!Error: ${request.responseCode}",
            )
            request.disconnect()
            throw NetworkError.Unknown()
        }

            val requestDuration = (System.currentTimeMillis() - startTime) / 1000.0
            val requestId =
                try {
                    getRequestId(request, auth, requestDuration)
                } catch (e: Throwable) {
                    return@retrying Either.Failure(if (e is NetworkError) e else NetworkError.Unknown())
                }

            Logger.debug(
                LogLevel.debug,
                LogScope.network,
                "Request Completed",
                mapOf(
                    "request" to request.toString(),
                    "api_key" to auth,
                    "url" to (request.url?.toString() ?: "unknown"),
                    "request_id" to requestId,
                    "request_duration" to requestDuration,
                ),
            )

            val value: Either<Response, NetworkError> =
                try {
                    Either.Success(this.json.decodeFromString<Response>(responseMessage))
                } catch (e: Throwable) {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.network,
                        "Request Error",
                        mapOf(
                            "request" to request.toString(),
                            "api_key" to auth,
                            "url" to (request.url?.toString() ?: "unknown"),
                            "message" to "Unable to decode response to type ${Response::class.simpleName}",
                            "info" to responseMessage,
                            "request_duration" to requestDuration,
                        ),
                    )
                    e.printStackTrace()
                    Either.Failure(NetworkError.Decoding(e))
                }

            return@retrying value
        }
    }

    @Throws(NetworkError::class)
    fun getRequestId(
        request: HttpURLConnection,
        auth: String,
        requestDuration: Double,
    ): String {
        var requestId = "unknown"

        val id = request.getHeaderField("x-request-id")
        if (id != null) {
            requestId = id
        }

        when (request.responseCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                Logger.debug(
                    LogLevel.error,
                    LogScope.network,
                    "Unable to Authenticate",
                    mapOf(
                        "request" to request.toString(),
                        "api_key" to auth,
                        "url" to request.url.toString(),
                        "request_id" to requestId,
                        "request_duration" to requestDuration,
                    ),
                )
                throw NetworkError.NotAuthenticated()
            }

            HttpURLConnection.HTTP_NOT_FOUND -> {
                Logger.debug(
                    LogLevel.error,
                    LogScope.network,
                    "Not Found",
                    mapOf(
                        "request" to request.toString(),
                        "api_key" to auth,
                        "url" to request.url.toString(),
                        "request_id" to requestId,
                        "request_duration" to requestDuration,
                    ),
                )
                throw NetworkError.NotFound()
            }
        }
        return requestId
    }
}
