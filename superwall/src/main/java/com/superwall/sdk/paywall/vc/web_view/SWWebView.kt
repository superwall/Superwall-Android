package com.superwall.sdk.paywall.vc.web_view

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.*
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.game.dispatchKeyEvent
import com.superwall.sdk.game.dispatchMotionEvent
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandler
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandlerDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@Suppress("ktlint:standard:class-naming")
interface _SWWebViewDelegate {
    val info: PaywallInfo
}

interface SWWebViewDelegate :
    _SWWebViewDelegate,
    PaywallMessageHandlerDelegate

class SWWebView(
    context: Context,
    private val sessionEventsManager: SessionEventsManager,
    val messageHandler: PaywallMessageHandler,
) : WebView(context) {
    var delegate: SWWebViewDelegate? = null

    init {

        addJavascriptInterface(messageHandler, "SWAndroid")

        val webSettings = this.settings
        webSettings.javaScriptEnabled = true
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        webSettings.textZoom = 100

        // Enable inline media playback, requires API level 17
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.mediaPlaybackRequiresUserGesture = false
        }

        this.setBackgroundColor(Color.TRANSPARENT)

        this.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // Don't log anything
                    return true
                }
            }
        // Set a WebViewClient
        this.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    return true // This will prevent the loading of URLs inside your WebView
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    // Do something when page loading finished
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    CoroutineScope(Dispatchers.Main).launch {
                        trackPaywallError(error)
                    }
                }
            }
    }

    // ???
    // https://stackoverflow.com/questions/20968707/capturing-keypress-events-in-android-webview
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection = BaseInputConnection(this, false)

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || !Superwall.instance.options.isGameControllerEnabled) {
            return super.dispatchKeyEvent(event)
        }
        Superwall.instance.dispatchKeyEvent(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || !Superwall.instance.options.isGameControllerEnabled) {
            return super.dispatchGenericMotionEvent(event)
        }
        Superwall.instance.dispatchMotionEvent(event)
        return true
    }

    override fun loadUrl(url: String) {
        // Parse the url and add the query parameter
        val uri = Uri.parse(url)

        // Add a query parameter
        val builder = uri.buildUpon()
        builder.appendQueryParameter("platform", "android")
        builder.appendQueryParameter("transport", "android")
        builder.appendQueryParameter("debug", "true")
//        builder.appendQueryParameter("pjs", "http://10.0.2.2:4444/runtime/dev/entrypoint.js")
        val newUri = builder.build()

        // Use the new URL
        val urlString = newUri.toString()
        println("SWWebView.loadUrl: $urlString")

        super.loadUrl(urlString)
    }

    private suspend fun trackPaywallError(webResourceError: WebResourceError) {
        delegate?.paywall?.webviewLoadingInfo?.failAt = Date()

        val paywallInfo = delegate?.info ?: return

        val trackedEvent =
            InternalSuperwallEvent.PaywallWebviewLoad(
                state =
                    InternalSuperwallEvent.PaywallWebviewLoad.State.Fail(
                        "Code: ${webResourceError.errorCode} - ${webResourceError.description}",
                    ),
                paywallInfo = paywallInfo,
            )
        Superwall.instance.track(trackedEvent)
    }
}
