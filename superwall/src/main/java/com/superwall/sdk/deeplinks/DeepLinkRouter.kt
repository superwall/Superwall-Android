package com.superwall.sdk.deeplinks

import android.net.Uri
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.config.toConfigurationStatus
import com.superwall.sdk.debug.DebugManager
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.utilities.withErrorTracking
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.ConcurrentLinkedDeque

class DeepLinkRouter internal constructor(
    val redeemer: WebPaywallRedeemer,
    val ioScope: IOScope,
    val debugManager: DebugManager,
    val track: suspend (TrackableSuperwallEvent) -> Unit,
    private val configurationState: () -> Flow<ConfigurationStatus>,
) {
    /**
     * Kept for binary compatibility. The SDK builds [DeepLinkRouter] with the internal constructor;
     * this one reads the configuration state from the configured SDK.
     */
    @Deprecated("Internal SDK class; construct it through Superwall.configure.")
    constructor(
        redeemer: WebPaywallRedeemer,
        ioScope: IOScope = IOScope(),
        debugManager: DebugManager,
        track: suspend (TrackableSuperwallEvent) -> Unit,
    ) : this(
        redeemer = redeemer,
        ioScope = ioScope,
        debugManager = debugManager,
        track = track,
        configurationState = { SuperwallGraph.requireContainer().configManager.configurationStatus },
    )

    companion object {
        private var unhandledDeepLinks = ConcurrentLinkedDeque<Uri>()

        fun handleDeepLink(uri: Uri): Result<Boolean> {
            val configuredContainer =
                SuperwallGraph.container?.takeIf {
                    it.configManager.configState.value
                        .toConfigurationStatus() is ConfigurationStatus.Configured
                }
            if (configuredContainer != null) {
                configuredContainer.deepLinkRouter.handleDeepLink(uri)
            } else {
                unhandledDeepLinks.add(uri)
            }
            Logger.debug(
                LogLevel.info,
                LogScope.deepLinks,
                "Superwall handling provided deep link",
            )

            val handled =
                uri.redeemableCode.isSuccess || DebugManager.outcomeForDeepLink(uri).isSuccess

            if (handled) {
                return Result.success(true)
            } else {
                Logger.debug(
                    LogLevel.info,
                    LogScope.deepLinks,
                    "Superwall not handling the provided deep link",
                )
                return Result.failure(IllegalArgumentException("Not a superwall link"))
            }
        }
    }

    init {
        ioScope.launch {
            Superwall.hasInitialized.first { it }
            configurationState().first { it is ConfigurationStatus.Configured }
            while (unhandledDeepLinks.isNotEmpty()) {
                val next = unhandledDeepLinks.pop()
                this@DeepLinkRouter.handleDeepLink(next)
                unhandledDeepLinks.remove(next)
            }
        }
    }

    fun handleDeepLink(uri: Uri): Result<Boolean> =
        withErrorTracking<Boolean> {
            ioScope.launch {
                track(InternalSuperwallEvent.DeepLink(uri = URI.create(uri.toString())))
            }
            val handledAsRedemption =
                redeemer.deepLinkReferrer
                    .handleDeepLink(uri)
                    .onSuccess {
                        ioScope.launch {
                            redeemer.redeem(WebPaywallRedeemer.RedeemType.Code(it))
                        }
                    }.isSuccess

            val result: Boolean =
                if (handledAsRedemption) {
                    true
                } else {
                    debugManager.handle(deepLinkUrl = uri)
                }
            result
        }.toResult()
}

internal val Uri.redeemableCode: Result<String>
    get() {
        val failure =
            Result.failure<String>(UnsupportedOperationException("Link not valid for redemption"))
        return if (host?.contains("superwall") == true && lastPathSegment.equals("redeem")) {
            (getQueryParameter("code") ?: getQueryParameter("codes"))?.let {
                Result.success(it)
            } ?: failure
        } else {
            failure
        }
    }
