package com.superwall.sdk.network.session


import LogLevel
import LogScope
import Logger
import com.superwall.sdk.misc.retrying
import com.superwall.sdk.models.SerializableEntity
import com.superwall.sdk.network.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.net.HttpURLConnection

internal sealed class NetworkError(message: String) : Throwable(message) {
    class Unknown : NetworkError("An unknown error occurred.")
    class NotAuthenticated : NetworkError("Unauthorized.")
    class Decoding : NetworkError("Decoding error.")
    class NotFound : NetworkError("Not found.")
    class InvalidUrl : NetworkError("URL invalid.")
}


internal class CustomHttpUrlConnection {


    val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }
//
//    suspend fun request(endpoint: String): String {
//        var result = ""
//        withContext(Dispatchers.IO) {
//            try {
//                val url = URL(endpoint)
//                val connection = url.openConnection() as HttpURLConnection
//                connection.requestMethod = "GET"
//                connection.doInput = true
//                connection.doOutput = true
//
//                val auth = connection.getRequestProperty("Authorization")
//                    ?: throw NetworkError.NotAuthenticated()
//
//                val startTime = System.currentTimeMillis()
//                val responseCode = connection.responseCode
//                if (responseCode == HttpURLConnection.HTTP_OK) {
//                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
//                    val response = StringBuilder()
//                    var responseLine: String?
//                    while (reader.readLine().also { responseLine = it } != null) {
//                        response.append(responseLine!!.trim { it <= ' ' })
//                    }
//                    result = response.toString()
//                } else {
//                    when (responseCode) {
//                        HttpURLConnection.HTTP_UNAUTHORIZED -> throw NetworkError.NotAuthenticated()
//                        HttpURLConnection.HTTP_NOT_FOUND -> throw NetworkError.NotFound()
//                        else -> throw NetworkError.Unknown()
//                    }
//                }
//
//                val requestDuration = System.currentTimeMillis() - startTime
//                val requestId = connection.getHeaderField("x-request-id") ?: "unknown"
//
//                // Log here the request completed
//
//            } catch (e: Exception) {
//                // Log here the request error
//                throw NetworkError.Decoding()
//            }
//        }
//        return result
//    }


    @Throws(NetworkError::class)
    suspend inline fun <reified Response : SerializableEntity> request(
        endpoint: Endpoint<Response>,
        noinline  isRetryingCallback: (() -> Unit)? = null
    ): Response {
        val request = endpoint.makeRequest() ?: throw NetworkError.Unknown()

        val auth =
            request.getRequestProperty("Authorization") ?: throw NetworkError.NotAuthenticated()

        Logger.debug(
            LogLevel.debug,
            LogScope.network,
            "Request Started",
            mapOf(
                "url" to (request.url?.toString() ?: "unknown")
            )
        )

        val startTime = System.currentTimeMillis()

        val responseCode: Int = retrying(
            coroutineContext = Dispatchers.IO,
            maxRetryCount = endpoint.retryCount,
            isRetryingCallback = isRetryingCallback
        ) {
            request.responseCode
        }

        var responseMessage: String? = null
        if (responseCode == HttpURLConnection.HTTP_OK) {
            responseMessage = request.inputStream.bufferedReader().use { it.readText() }
            request.disconnect()
        } else {
            println("!!!Error: ${request.responseCode}")
            request.disconnect()
            throw NetworkError.Unknown()
        }

        val requestDuration = (System.currentTimeMillis() - startTime) / 1000.0
        val requestId = try {
            getRequestId(request, auth, requestDuration)
        } catch (e: Exception) {
            throw NetworkError.Unknown()
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
                "request_duration" to requestDuration
            )
        )

        val value: Response? = try {
            this.json.decodeFromString<Response>(responseMessage)
        } catch (e: Exception) {
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
                    "request_duration" to requestDuration
                )
            )
            println("!!!Error: ${e.message}")
            throw NetworkError.Decoding()
        }

        return value ?: throw NetworkError.Decoding()
    }


    @Throws(NetworkError::class)
    fun getRequestId(
        request: HttpURLConnection,
        auth: String,
        requestDuration: Double
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
                        "request_duration" to requestDuration
                    )
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
                        "request_duration" to requestDuration
                    )
                )
                throw NetworkError.NotFound()
            }
        }
        return requestId
    }
}
