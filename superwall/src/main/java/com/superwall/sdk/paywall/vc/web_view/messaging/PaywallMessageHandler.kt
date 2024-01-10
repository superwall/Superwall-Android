package com.superwall.sdk.paywall.vc.web_view.messaging

import LogLevel
import LogScope
import Logger
import TemplateLogic
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.trigger_session.LoadState
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.web_view.PaywallMessage
import com.superwall.sdk.paywall.vc.web_view.WrappedPaywallMessages
import com.superwall.sdk.paywall.vc.web_view.parseWrappedPaywallMessages
import kotlinx.coroutines.*
import java.net.URL
import java.util.*

interface PaywallMessageHandlerDelegate {
    val request: PresentationRequest?
    var paywall: Paywall
    val info: PaywallInfo
    val webView: WebView
    var loadingState: PaywallLoadingState
    val isActive: Boolean

    fun eventDidOccur(paywallWebEvent: PaywallWebEvent)
    fun openDeepLink(url: String)
    fun presentSafariInApp(url: String)
    fun presentSafariExternal(url: String)
}

class PaywallMessageHandler(
    private val sessionEventsManager: SessionEventsManager,
    private val factory: VariablesFactory
) {

    public var delegate: PaywallMessageHandlerDelegate? = null


    @JavascriptInterface
    fun postMessage(message: String) {

        // Print out the message to the console using Log.d
        Log.d("SWWebViewInterface", message)

        // Attempt to parse the message to json
        // and print out the version number
        val wrappedPaywallMessages: WrappedPaywallMessages
        try {
            wrappedPaywallMessages = parseWrappedPaywallMessages(message)
        } catch (e: Throwable) {
            Log.e("SWWebViewInterface", "Error parsing message", e)
            return
        }

        // Loop through the messages and print out the event name
        for (paywallMessage in wrappedPaywallMessages.payload.messages) {
            Log.d("SWWebViewInterface", paywallMessage.javaClass.simpleName)
            handle(paywallMessage)
        }
    }

    fun handle(message: PaywallMessage) {
        println("!! PaywallMessageHandler: Handling message: $message ${delegate?.paywall}, delegeate: $delegate")
        val paywall = delegate?.paywall ?: return
        println("!! PaywallMessageHandler: Paywall: $paywall, delegeate: $delegate")
        println("!! PaywallMessageHandler: delegeate: $delegate")
        when (message) {
            is PaywallMessage.TemplateParamsAndUserAttributes ->
                MainScope().launch { passTemplatesToWebView(paywall) }
            is PaywallMessage.OnReady -> {
                delegate?.paywall?.paywalljsVersion = message.paywallJsVersion
                val loadedAt = Date()
                println("!!Ready!!")
                MainScope().launch { didLoadWebView(paywall, loadedAt) }
            }
            is PaywallMessage.Close -> {
                hapticFeedback()
                delegate?.eventDidOccur(PaywallWebEvent.Closed)
            }
            is PaywallMessage.OpenUrl -> openUrl(message.url)
            is PaywallMessage.OpenUrlInSafari -> openUrlInSafari(message.url)
            is PaywallMessage.OpenDeepLink -> openDeepLink(URL(message.url.toString()))
            is PaywallMessage.Restore -> restorePurchases()
            is PaywallMessage.Purchase -> purchaseProduct(withId = message.productId)
            is PaywallMessage.Custom -> handleCustomEvent(message.data)
            else -> {
                println("!! PaywallMessageHandler: Unknown message type: $message")
            }
        }
    }

    // Passes the templated variables and params to the webview.
// This is called every paywall open incase variables like user attributes have changed.
    private suspend fun passTemplatesToWebView(paywall: Paywall) {
        val eventData = delegate?.request?.presentationInfo?.eventData
        val templates = TemplateLogic.getBase64EncodedTemplates(
            paywall = paywall,
            event = eventData,
            factory = factory
        )

        val templateScript = """
      window.paywall.accept64('$templates');
    """

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallViewController,
            message = "Posting Message",
            info = mapOf("message" to templateScript)
        )

        withContext(Dispatchers.Main) {
            delegate?.webView?.evaluateJavascript(templateScript) { error ->
                if (error != null) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallViewController,
                        message = "Error Evaluating JS",
                        info = mapOf("message" to templateScript),
                        error = java.lang.Exception(error)
                    )
                }
            }
        }
    }

    // Passes in the HTML substitutions, templates and other scripts to make the webview feel native.
    private suspend fun didLoadWebView(paywall: Paywall, loadedAt: Date) {
        val coroutineScope = CoroutineScope(Dispatchers.IO)
        coroutineScope.launch {
            val delegate = this@PaywallMessageHandler.delegate
            if (delegate != null) {
                delegate.paywall.webviewLoadingInfo.endAt = loadedAt

                val paywallInfo = delegate.info
                val trackedEvent = InternalSuperwallEvent.PaywallWebviewLoad(
                    state = InternalSuperwallEvent.PaywallWebviewLoad.State.Complete(),
                    paywallInfo = paywallInfo
                )
                Superwall.instance.track(trackedEvent)
            }
        }

        println("!! PaywallMessageHandler: didLoadWebView")

        val htmlSubstitutions = paywall.htmlSubstitutions
        val eventData = delegate?.request?.presentationInfo?.eventData
        val templates = TemplateLogic.getBase64EncodedTemplates(
            paywall = paywall,
            event = eventData,
            factory = factory
        )
        val scriptSrc = """
      window.paywall.accept64('$templates');
      window.paywall.accept64('$htmlSubstitutions');
    """

        println("!! PaywallMessageHandler: $scriptSrc")

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallViewController,
            message = "Posting Message",
            info = mapOf("message" to scriptSrc)
        )

        CoroutineScope(Dispatchers.Main).launch {
            delegate?.webView?.evaluateJavascript(scriptSrc) { error ->
                if (error != null) {
                    println("!! PaywallMessageHandler: Error: $error")
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallViewController,
                        message = "Error Evaluating JS",
                        info = mapOf("message" to scriptSrc),
                        error = java.lang.Exception(error)
                    )
                }
                delegate?.loadingState = PaywallLoadingState.Ready()
            }

            // block selection
            val selectionString =
                "var css = '*{-webkit-touch-callout:none;-webkit-user-select:none} .w-webflow-badge { display: none !important; }'; " +
                        "var head = document.head || document.getElementsByTagName('head')[0]; " +
                        "var style = document.createElement('style'); style.type = 'text/css'; " +
                        "style.appendChild(document.createTextNode(css)); head.appendChild(style); "

            delegate?.webView?.evaluateJavascript(selectionString, null)

            val preventZoom = "var meta = document.createElement('meta');" +
                    "meta.name = 'viewport';" +
                    "meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
                    "var head = document.getElementsByTagName('head')[0];" +
                    "head.appendChild(meta);"
            delegate?.webView?.evaluateJavascript(preventZoom, null)
        }
    }

    private fun openUrl(url: URL) {
        detectHiddenPaywallEvent(
            "openUrl",
            mapOf("url" to url.toString())
        )
        hapticFeedback()
        delegate?.eventDidOccur(PaywallWebEvent.OpenedURL(url))
        delegate?.presentSafariInApp(url.toString())
    }

    private fun openUrlInSafari(url: URL) {
        detectHiddenPaywallEvent(
            "openUrlInSafari",
            mapOf("url" to url)
        )
        hapticFeedback()
        delegate?.eventDidOccur(PaywallWebEvent.OpenedUrlInSafari(url))
        delegate?.presentSafariExternal(url.toString())
    }

    private fun openDeepLink(url: URL) {
        detectHiddenPaywallEvent(
            "openDeepLink",
            mapOf("url" to url)
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
            mapOf("custom_event" to customEvent)
        )
        delegate?.eventDidOccur(PaywallWebEvent.Custom(customEvent))
    }

    private fun detectHiddenPaywallEvent(
        eventName: String,
        userInfo: Map<String, Any>? = null
    ) {
        val delegateIsActive = delegate?.isActive

        if (delegateIsActive == true) {
            return
        }

        val paywallDebugDescription = Superwall.instance.paywallViewController.toString()

        var info: MutableMap<String, Any> = mutableMapOf(
            "self" to this,
            "Superwall.instance.paywallViewController" to paywallDebugDescription,
            "event" to eventName
        )
        userInfo?.let {
            info.putAll(userInfo)
        }

        Logger.debug(
            logLevel = LogLevel.error,
            scope = LogScope.paywallViewController,
            message = "Received Event on Hidden Superwall",
            info = info
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