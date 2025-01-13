package com.superwall.sdk.web

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.newBuilder
import com.android.installreferrer.api.InstallReferrerStateListener
import com.superwall.sdk.misc.IOScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface CheckForReferral {
    suspend fun checkForReferral(): Result<String>
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
            referrerClient.installReferrer.installReferrer
        }.let {
            if (it == null) {
                Result.failure(IllegalStateException("Play store cannot connect"))
            } else {
                Result.success(it)
            }
        }
}
