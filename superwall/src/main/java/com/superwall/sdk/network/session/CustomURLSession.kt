package com.superwall.sdk.network.session

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.flatMap
import com.superwall.sdk.misc.retrying
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.network.NetworkRequestData
import com.superwall.sdk.network.RequestExecutor
import com.superwall.sdk.network.authHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class CustomHttpUrlConnection(
    val scope: CoroutineScope,
    val json: Json,
    val requestExecutor: RequestExecutor,
) {
    @OptIn(InternalSerializationApi::class)
    @Throws(NetworkError::class)
    suspend inline fun <reified Response : @Serializable Any> request(
        crossinline buildRequestData: suspend () -> NetworkRequestData<Response>,
        retryCount: Int,
        noinline isRetryingCallback: (suspend () -> Unit)? = null,
    ): Either<Response, NetworkError> {
        return retrying(
            coroutineContext = scope.coroutineContext,
            maxRetryCount = retryCount,
            isRetryingCallback = isRetryingCallback,
        ) {
            val requestData = buildRequestData()
            return@retrying requestExecutor.execute(requestData).flatMap {
                try {
                    Either.Success(
                        this.json.decodeFromString<Response>(
                            it.responseMessage,
                        ),
                    )
                } catch (e: Throwable) {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.network,
                        "Request Error",
                        mapOf(
                            "request" to it.toString(),
                            "api_key" to it.authHeader(),
                            "url" to (requestData.url?.toString() ?: "unknown"),
                            "message" to "Unable to decode response to type ${Response::class.simpleName}",
                            "info" to it.responseMessage,
                            "request_duration" to it.duration,
                        ),
                    )
                    e.printStackTrace()
                    Either.Failure(NetworkError.Decoding(e))
                }
            }
        }
    }
}
