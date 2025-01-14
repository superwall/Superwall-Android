package com.superwall.sdk.paywall.view.webview.messaging

import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.paywall.view.webview.PaywallMessage
import com.superwall.sdk.paywall.view.webview.parseWrappedPaywallMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

interface WebEventDelegate {
    suspend fun handle(message: PaywallMessage)
}

class RawWebMessageHandler(
    private val delegate: WebEventDelegate,
) : WebViewClient() {
    @JavascriptInterface
    public fun postMessage(message: String) {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallView,
            message = "Did Receive Message",
            info = hashMapOf("message" to message),
        )

        val bodyString = message
        val bodyData = bodyString.toByteArray(Charsets.UTF_8)

        val wrappedPaywallMessages =
            try {
                parseWrappedPaywallMessages(bodyData.decodeToString())
            } catch (e: Throwable) {
                Logger.debug(
                    logLevel = LogLevel.warn,
                    scope = LogScope.paywallView,
                    message = "Invalid WrappedPaywallEvent",
                    info = hashMapOf("message" to message),
                )
                return
            }

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallView,
            message = "Body Converted",
            info = hashMapOf("message" to message, "events" to wrappedPaywallMessages),
        )

        val messages = wrappedPaywallMessages.payload.messages
        messages.forEach { m ->
            CoroutineScope(Dispatchers.Main).launch {
                delegate.handle(m)
            }
        }
    }
}
