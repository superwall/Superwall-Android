package com.superwall.sdk.paywall.view.webview.messaging

import TemplateLogic
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvents
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.PaywallMessage
import com.superwall.sdk.paywall.view.webview.WrappedPaywallMessages
import com.superwall.sdk.paywall.view.webview.parseWrappedPaywallMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.LinkedList
import java.util.Queue

interface PaywallMessageHandlerDelegate {
    val request: PresentationRequest?
    var paywall: Paywall
    val info: PaywallInfo
    val webView: WebView
    var loadingState: PaywallLoadingState
    val isActive: Boolean

    fun eventDidOccur(paywallWebEvent: PaywallWebEvent)

    fun openDeepLink(url: String)

    fun presentBrowserInApp(url: String)

    fun presentBrowserExternal(url: String)
}

class PaywallMessageHandler(
    private val sessionEventsManager: SessionEventsManager,
    private val factory: VariablesFactory,
    private val mainScope: MainScope,
    private val ioScope: CoroutineScope,
    private val json: Json = Json { encodeDefaults = true },
) {
    private companion object {
        val selectionString =
            """var css = '*{-webkit-touch-callout:none;-webkit-user-select:none} .w-webflow-badge { display: none !important; }';
                    var head = document.head || document.getElementsByTagName('head')[0];
                    var style = document.createElement('style'); style.type = 'text/css';
                    style.appendChild(document.createTextNode(css)); head.appendChild(style); """
        val preventZoom =
            """var meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                        var head = document.getElementsByTagName('head')[0];
                        head.appendChild(meta);"""
    }

    var delegate: PaywallMessageHandlerDelegate? = null
    private val queue: Queue<PaywallMessage> = LinkedList()

    @JavascriptInterface
    fun postMessage(message: String) {
        // Print out the message to the console using Log.d
        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
            "SWWebViewInterface: $message",
        )

        // Attempt to parse the message to json
        // and print out the version number
        val wrappedPaywallMessages: WrappedPaywallMessages
        try {
            wrappedPaywallMessages = parseWrappedPaywallMessages(message)
        } catch (e: Throwable) {
            Logger.debug(
                LogLevel.debug,
                LogScope.superwallCore,
                "SWWebViewInterface: Error parsing message - $e",
            )
            return
        }

        // Loop through the messages and print out the event name
        for (paywallMessage in wrappedPaywallMessages.payload.messages) {
            Logger.debug(
                LogLevel.debug,
                LogScope.superwallCore,
                "SWWebViewInterface: ${paywallMessage.javaClass.simpleName}",
            )
            handle(paywallMessage)
        }
    }

    fun handle(message: PaywallMessage) {
        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
            "!! PaywallMessageHandler: Handling message: $message ${delegate?.paywall}, delegeate: $delegate",
        )
        val paywall = delegate?.paywall ?: return
        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
            "!! PaywallMessageHandler: Paywall: $paywall, delegeate: $delegate",
        )
        when (message) {
            is PaywallMessage.TemplateParamsAndUserAttributes ->
                ioScope.launch { passTemplatesToWebView(paywall) }

            is PaywallMessage.OnReady -> {
                delegate?.paywall?.paywalljsVersion = message.paywallJsVersion
                val loadedAt = Date()
                Logger.debug(
                    LogLevel.debug,
                    LogScope.superwallCore,
                    "!! PaywallMessageHandler: Ready !!",
                )
                ioScope.launch { didLoadWebView(paywall, loadedAt) }
            }

            is PaywallMessage.Close -> {
                hapticFeedback()
                delegate?.eventDidOccur(PaywallWebEvent.Closed)
            }

            is PaywallMessage.OpenUrl -> openUrl(message.url)
            is PaywallMessage.OpenUrlInBrowser -> openUrlInBrowser(message.url)
            is PaywallMessage.OpenDeepLink -> openDeepLink(Uri.parse(message.url.toString()))
            is PaywallMessage.Restore -> restorePurchases()
            is PaywallMessage.Purchase -> purchaseProduct(withId = message.productId)
            is PaywallMessage.PaywallOpen -> {
                if (delegate?.paywall?.paywalljsVersion == null) {
                    queue.offer(message)
                } else {
                    ioScope.launch {
                        pass(eventName = SuperwallEvents.PaywallOpen.rawName, paywall = paywall)
                    }
                }
            }

            is PaywallMessage.PaywallClose -> {
                if (delegate?.paywall?.paywalljsVersion == null) {
                    queue.offer(message)
                } else {
                    ioScope.launch {
                        val eventName = SuperwallEvents.PaywallClose.rawName
                        pass(eventName = eventName, paywall = paywall)
                    }
                }
            }

            is PaywallMessage.Custom -> handleCustomEvent(message.data)
            is PaywallMessage.CustomPlacement -> handleCustomPlacement(message.name, message.params)
            else -> {
                Logger.debug(
                    LogLevel.error,
                    LogScope.superwallCore,
                    "!! PaywallMessageHandler: Unknown message type: $message",
                )
            }
        }
    }

    private suspend fun pass(
        eventName: String,
        paywall: Paywall,
    ) {
        val eventList =
            listOf(
                mapOf(
                    "event_name" to eventName,
                    "paywall_id" to paywall.databaseId,
                    "paywall_identifier" to paywall.identifier,
                ),
            )
        val jsonString = json.encodeToString(eventList)

        // Encode the JSON string to Base64
        val base64Event =
            Base64.encodeToString(jsonString.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        passMessageToWebView(base64String = base64Event)
    }

    // Passes the templated variables and params to the webview.
    // This is called every paywall open incase variables like user attributes have changed.
    private suspend fun passTemplatesToWebView(paywall: Paywall) {
        val eventData = delegate?.request?.presentationInfo?.eventData
        val templates =
            TemplateLogic.getBase64EncodedTemplates(
                paywall = paywall,
                event = eventData,
                factory = factory,
                json = json,
            )
        passMessageToWebView(base64String = templates)
    }

    private suspend fun passMessageToWebView(base64String: String) {
        val templateScript = """
      window.paywall.accept64('$base64String');
    """

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallView,
            message = "Posting Message",
            info = mapOf("message" to templateScript),
        )

        withContext(Dispatchers.Main) {
            delegate?.webView?.evaluateJavascript(templateScript) { error ->
                if (error != null) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallView,
                        message = "Error Evaluating JS",
                        info = mapOf("message" to templateScript),
                        error = java.lang.Exception(error),
                    )
                }
            }
        }
    }

    // Passes in the HTML substitutions, templates and other scripts to make the webview feel native.
    private suspend fun didLoadWebView(
        paywall: Paywall,
        loadedAt: Date,
    ) {
        ioScope.launch {
            val delegate = this@PaywallMessageHandler.delegate
            if (delegate != null) {
                delegate.paywall.webviewLoadingInfo.endAt = loadedAt

                val paywallInfo = delegate.info
                val trackedEvent =
                    InternalSuperwallEvent.PaywallWebviewLoad(
                        state = InternalSuperwallEvent.PaywallWebviewLoad.State.Complete(),
                        paywallInfo = paywallInfo,
                    )
                Superwall.instance.track(trackedEvent)
            }
        }

        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
            "!! PaywallMessageHandler: didLoadWebView",
        )

        val htmlSubstitutions = paywall.htmlSubstitutions
        val eventData = delegate?.request?.presentationInfo?.eventData
        val templates =
            TemplateLogic.getBase64EncodedTemplates(
                paywall = paywall,
                event = eventData,
                factory = factory,
                json = json,
            )
        val scriptSrc = """
      window.paywall.accept64('$templates');
      window.paywall.accept64('$htmlSubstitutions');
    """

        Logger.debug(
            LogLevel.debug,
            LogScope.superwallCore,
            "!! PaywallMessageHandler: $scriptSrc",
        )

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallView,
            message = "Posting Message",
            info = mapOf("message" to scriptSrc),
        )

        mainScope.launch {
            delegate?.webView?.evaluateJavascript(scriptSrc) { error ->
                if (error != null) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallView,
                        message = "Error Evaluating JS",
                        info = mapOf("message" to scriptSrc),
                        error = java.lang.Exception(error),
                    )
                }
            }

            // block selection
            delegate?.webView?.evaluateJavascript(selectionString, null)
            delegate?.webView?.evaluateJavascript(preventZoom, null)
            ioScope.launch {
                mainScope.launch {
                    while (queue.isNotEmpty()) {
                        val item = queue.remove()
                        handle(item)
                    }
                    delegate?.loadingState = PaywallLoadingState.Ready()
                }
            }
        }
    }

    private fun openUrl(url: URI) {
        detectHiddenPaywallEvent(
            "openUrl",
            mapOf("url" to url.toString()),
        )
        hapticFeedback()
        delegate?.eventDidOccur(PaywallWebEvent.OpenedURL(url))
        delegate?.presentBrowserInApp(url.toString())
    }

    private fun openUrlInBrowser(url: URI) {
        detectHiddenPaywallEvent(
            "openUrlInSafari",
            mapOf("url" to url),
        )
        hapticFeedback()
        delegate?.eventDidOccur(PaywallWebEvent.OpenedUrlInChrome(url))
        delegate?.presentBrowserExternal(url.toString())
    }

    private fun openDeepLink(url: Uri) {
        detectHiddenPaywallEvent(
            "openDeepLink",
            mapOf("url" to url),
        )
        hapticFeedback()
        delegate?.openDeepLink(url.toString())
    }

    private fun restorePurchases() {
        detectHiddenPaywallEvent("restore")
        hapticFeedback()
        delegate?.eventDidOccur(PaywallWebEvent.InitiateRestore)
    }

    private fun purchaseProduct(withId: String) {
        detectHiddenPaywallEvent("purchase")
        hapticFeedback()
        delegate?.eventDidOccur(PaywallWebEvent.InitiatePurchase(withId))
    }

    private fun handleCustomEvent(customEvent: String) {
        detectHiddenPaywallEvent(
            "custom",
            mapOf("custom_event" to customEvent),
        )
        delegate?.eventDidOccur(PaywallWebEvent.Custom(customEvent))
    }

    private fun handleCustomPlacement(
        name: String,
        params: JSONObject,
    ) {
        delegate?.eventDidOccur(PaywallWebEvent.CustomPlacement(name, params))
    }

    private fun detectHiddenPaywallEvent(
        eventName: String,
        userInfo: Map<String, Any>? = null,
    ) {
        val delegateIsActive = delegate?.isActive

        if (delegateIsActive == true) {
            return
        }

        val paywallDebugDescription = Superwall.instance.paywallView.toString()

        var info: MutableMap<String, Any> =
            mutableMapOf(
                "self" to this,
                "Superwall.instance.paywallViewController" to paywallDebugDescription,
                "event" to eventName,
            )
        userInfo?.let {
            info.putAll(userInfo)
        }

        Logger.debug(
            logLevel = LogLevel.error,
            scope = LogScope.paywallView,
            message = "Received Event on Hidden Superwall",
            info = info,
        )
    }

    private fun hapticFeedback() {
        val isHapticFeedbackEnabled = Superwall.instance.options.paywalls.isHapticFeedbackEnabled
        val isGameControllerEnabled = Superwall.instance.options.isGameControllerEnabled

        if (isHapticFeedbackEnabled == false || isGameControllerEnabled == true) {
            return
        }

        // Replace this with your platform-specific implementation for haptic feedback
        // Android doesn't have a direct equivalent to UIImpactFeedbackGenerator
        // TODO: Implement haptic feedback
    }
}
