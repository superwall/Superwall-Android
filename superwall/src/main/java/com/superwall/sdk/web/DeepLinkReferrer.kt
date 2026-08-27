package com.superwall.sdk.web

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.newBuilder
import com.android.installreferrer.api.InstallReferrerStateListener
import com.superwall.sdk.deeplinks.redeemableCode
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    private var referrerClient: InstallReferrerClient? = null
    private val referrerMutex = Mutex()
    private var cachedReferrerParams: Map<String, List<String>>? = null
    private val readyReferrerClient: InstallReferrerClient?
        get() {
            if (referrerClient?.isReady == true) {
                return referrerClient
            } else {
                return null
            }
        }

    init {
        referrerClient = newBuilder(context()).build()
        tryConnecting()
    }

    private class ConnectionListener(
        val finished: (responseCode: Int) -> Unit,
        val disconnected: () -> Unit,
    ) : InstallReferrerStateListener {
        override fun onInstallReferrerSetupFinished(responseCode: Int) {
            finished(responseCode)
        }

        override fun onInstallReferrerServiceDisconnected() {
            disconnected()
        }
    }

    fun tryConnecting(timeout: Int = 0) {
        try {
            val connect = {
                referrerClient?.startConnection(
                    ConnectionListener(
                        finished = {
                            when (it) {
                                InstallReferrerClient.InstallReferrerResponse.OK -> {
                                    readyReferrerClient?.installReferrer?.installReferrer
                                }

                                else -> {
                                    referrerClient?.endConnection()
                                    referrerClient = null
                                }
                            }
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
        } catch (e: Throwable) {
            kotlin.runCatching {
                referrerClient?.endConnection()
                referrerClient = null
            }
        }
    }

    override suspend fun checkForReferral(): Result<String> =
        try {
            val query = getInstallReferrerParams(30.seconds)
            val code = query["code"]?.firstOrNull()
            if (code == null) {
                Result.failure(IllegalStateException("Play store cannot connect"))
            } else {
                Result.success(code)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }

    suspend fun checkForMmpClickId(): Result<Long> =
        try {
            val query = getInstallReferrerParams(5.seconds)
            val clickId = query["sw_mmp_click_id"]?.firstOrNull()?.toLongOrNull()
            if (clickId == null) {
                Result.failure(IllegalStateException("Play store MMP click id not found"))
            } else {
                Result.success(clickId)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }

    override fun handleDeepLink(url: Uri): Result<String> =
        url.redeemableCode
            .onSuccess {
                Logger.debug(
                    LogLevel.info,
                    LogScope.webEntitlements,
                    "Successfully extracted code from deep link",
                )
            }.onFailure {
                Logger.debug(
                    LogLevel.error,
                    LogScope.webEntitlements,
                    "Failed to extract code from deep link",
                    info =
                        mapOf(
                            "URL" to url.toString(),
                            "Error" to (it.message ?: ""),
                        ),
                )
            }

    /**
     * Resolves the Play install referrer's query params.
     *
     * This instance is shared between web-checkout redemption and MMP install attribution, so
     * the result is memoized: the referrer is immutable for the lifetime of an install, and
     * the first reader would otherwise tear the connection down before the second one runs.
     * A timeout is *not* memoized — Play can simply be slow at cold start — and leaves the
     * client connected so a later call can still succeed.
     */
    private suspend fun getInstallReferrerParams(timeout: kotlin.time.Duration): Map<String, List<String>> =
        referrerMutex.withLock {
            cachedReferrerParams?.let { return@withLock it }

            val rawReferrer =
                withTimeoutOrNull(timeout) {
                    while (readyReferrerClient == null) {
                        delay(50)
                    }
                    readyReferrerClient?.installReferrer?.installReferrer?.toString()
                } ?: return@withLock emptyMap()

            referrerClient?.endConnection()
            referrerClient = null

            rawReferrer.getUrlParams().also { cachedReferrerParams = it }
        }

    private fun String.getUrlParams(): Map<String, List<String>> {
        val query = trim().removePrefix("?")
        if (query.isEmpty()) {
            return emptyMap()
        }

        val uri = Uri.parse("https://superwall.invalid/?$query")
        return uri.queryParameterNames.associateWith { key ->
            uri.getQueryParameters(key)
        }
    }
}
