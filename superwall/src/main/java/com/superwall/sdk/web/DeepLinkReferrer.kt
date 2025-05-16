package com.superwall.sdk.web

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.newBuilder
import com.android.installreferrer.api.InstallReferrerStateListener
import com.superwall.sdk.misc.IOScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface CheckForReferral {
    suspend fun checkForReferral(): Result<String>

    fun handleDeepLink(url: Uri): Result<String>
}

class DeepLinkReferrer(
    context: () -> Context,
    private val scope: IOScope,
) : CheckForReferral {
    private var referrerClient: InstallReferrerClient

    init {
        referrerClient = newBuilder(context()).build()
        tryConnecting()
    }

    private class ConnectionListener(
        val finished: () -> Unit,
        val disconnected: () -> Unit,
    ) : InstallReferrerStateListener {
        override fun onInstallReferrerSetupFinished(p0: Int) {
            finished()
        }

        override fun onInstallReferrerServiceDisconnected() {
            disconnected()
        }
    }

    fun tryConnecting(timeout: Int = 0) {
        val connect = {
            referrerClient.startConnection(
                ConnectionListener(
                    finished = {
                        referrerClient.installReferrer.installReferrer
                    },
                    disconnected = {
                        tryConnecting(timeout + 1000)
                    },
                ),
            )
        }
        if (timeout == 0) {
            connect()
        } else {
            scope.launch {
                withTimeout(timeout.milliseconds) {
                    connect()
                }
            }
        }
    }

    override suspend fun checkForReferral(): Result<String> =
        withTimeoutOrNull(30.seconds) {
            while (!referrerClient.isReady) {
                // no-op
            }
            referrerClient.installReferrer.installReferrer.toString()
        }.let {
            val query = it?.getUrlParams() ?: emptyMap()
            val code = query["code"]?.firstOrNull()
            if (code == null) {
                Result.failure(IllegalStateException("Play store cannot connect"))
            } else {
                Result.success(code)
            }
        }

    override fun handleDeepLink(url: Uri): Result<String> {
        val failure =
            Result.failure<String>(UnsupportedOperationException("Link not valid for redemption"))
        return if (url.host == "superwall" && url.lastPathSegment.equals("redeem")) {
            url.getQueryParameter("code")?.let {
                Result.success(it)
            } ?: failure
        } else {
            failure
        }
    }

    private fun String.getUrlParams(): Map<String, List<String>> {
        val urlParts = split("\\?".toRegex()).filter(String::isNotEmpty)
        if (urlParts.size < 2) {
            return emptyMap()
        }
        val query = urlParts[1]
        return listOf("item").associateWith { key ->
            query
                .split("&?$key=".toRegex())
                .filter(String::isNotEmpty)
                .map { URLDecoder.decode(it, "UTF-8") }
        }
    }
}
