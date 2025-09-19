package com.superwall.sdk.paywall.view.webview

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.superwall.sdk.Superwall
import com.superwall.sdk.game.dispatchKeyEvent
import com.superwall.sdk.game.dispatchMotionEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class CheckoutWebView(
    context: Context,
    private val onFinishedLoading: ((url: String) -> Unit)? = null,
) : WebView(context) {
    private val mainScope = MainScope()
    private val ioScope = IOScope()

    var onScrollChangeListener: OnScrollChangeListener? = null
    var onRenderProcessCrashed: ((RenderProcessGoneDetail) -> Unit) = {
        Logger.debug(
            LogLevel.error,
            LogScope.paywallView,
            "WebView crashed: $it",
        )
    }

    private companion object ChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // Don't log anything
            return true
        }
    }

    private var lastWebViewClient: WebViewClient? = null
    private var lastLoadedUrl: String? = null

    internal fun prepareWebview() {
        val webSettings = this.settings
        webSettings.javaScriptEnabled = true
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.domStorageEnabled = true
        webSettings.textZoom = 100
        // Enable inline media playback, requires API level 17
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.mediaPlaybackRequiresUserGesture = false
        }

        this.setBackgroundColor(Color.TRANSPARENT)
        this.webChromeClient = ChromeClient
    }

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
        lastLoadedUrl = url
        prepareWebview()
        val client =
            DefaultWebviewClient(
                forUrl = url,
                ioScope = CoroutineScope(Dispatchers.IO),
                onWebViewCrash = onRenderProcessCrashed,
            )
        this.webViewClient = client

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastWebViewClient = client
        }

        super.loadUrl(url)
    }

    override fun onScrollChanged(
        horizontalOrigin: Int,
        verticalOrigin: Int,
        oldHorizontal: Int,
        oldVertical: Int,
    ) {
        super.onScrollChanged(horizontalOrigin, verticalOrigin, oldHorizontal, oldVertical)
        onScrollChangeListener?.onScrollChanged(
            horizontalOrigin,
            verticalOrigin,
            oldHorizontal,
            oldVertical,
        )
    }

    override fun destroy() {
        onScrollChangeListener = null
        super.destroy()
    }

    interface OnScrollChangeListener {
        fun onScrollChanged(
            currentHorizontalScroll: Int,
            currentVerticalScroll: Int,
            oldHorizontalScroll: Int,
            oldcurrentVerticalScroll: Int,
        )
    }
}
