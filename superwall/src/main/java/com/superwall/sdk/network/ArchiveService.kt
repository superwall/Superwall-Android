package com.superwall.sdk.network

import android.net.Uri
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.onError
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import java.net.URL

class ArchiveService(
    private val customHttpUrlConnection: CustomHttpUrlConnection,
) {
    init {
        System.setProperty("http.maxConnections", "256")
    }

    suspend fun fetchRemoteFile(url: Uri): Either<FileResponse, NetworkError> =
        customHttpUrlConnection
            .downloadFileAt(
                url.toString().let {
                    URL(it)
                },
            ).onError {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.network,
                    message = "Request Failed while fetching file at: $url",
                    error = it,
                )
            }
}
