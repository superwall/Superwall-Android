package com.superwall.sdk.paywall.vc.web_view

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.game.dispatchKeyEvent
import com.superwall.sdk.game.dispatchMotionEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandler
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandlerDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.Date

@Suppress("ktlint:standard:class-naming")
interface _SWWebViewDelegate {
    val info: PaywallInfo
}

interface SWWebViewDelegate :
    _SWWebViewDelegate,
    PaywallMessageHandlerDelegate

class SWWebView(
    context: Context,
    val messageHandler: PaywallMessageHandler,
    private val onFinishedLoading: ((url: String) -> Unit)? = null,
) : WebView(context) {
    var delegate: SWWebViewDelegate? = null
    private val mainScope = MainScope()
    private val ioScope = IOScope()

    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(
            context,
            ScrollDisabledListener(),
        )
    }
    var onScrollChangeListener: OnScrollChangeListener? = null
    var scrollEnabled = true
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

    internal fun prepareWebview() {
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

        this.webChromeClient = ChromeClient
    }

    internal fun loadPaywallWithFallbackUrl(paywall: Paywall) {
        prepareWebview()
        val client =
            WebviewFallbackClient(
                config =
                    paywall.urlConfig
                        ?: run {
                            Logger.debug(
                                LogLevel.error,
                                LogScope.paywallView,
                                "Tried to start a paywall with multiple URLS but without URL config",
                            )
                            return
                        },
                mainScope = mainScope,
                ioScope = ioScope,
                loadUrl = {
                    loadUrl(it.url)
                },
                stopLoading = {
                    stopLoading()
                },
                onCrashed = onRenderProcessCrashed,
            )
        this.webViewClient = client
        listenToWebviewClientEvents(this.webViewClient as DefaultWebviewClient)
        client.loadWithFallback()
    }

    fun enableOffscreenRender() {
        settings.offscreenPreRaster = true
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
        prepareWebview()
        this.webViewClient =
            DefaultWebviewClient(
                forUrl = url,
                ioScope = CoroutineScope(Dispatchers.IO),
                onWebViewCrash = onRenderProcessCrashed,
            )
        listenToWebviewClientEvents(this.webViewClient as DefaultWebviewClient)
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
        Logger.debug(
            LogLevel.debug,
            LogScope.paywallView,
            "SWWebView.loadUrl: $urlString",
        )
        super.loadUrl(urlString)
    }

    private fun listenToWebviewClientEvents(client: DefaultWebviewClient) {
        ioScope.launch {
            client.webviewClientEvents
                .takeWhile {
                    mainScope
                        .async {
                            webViewClient == client
                        }.await()
                }.collect {
                    mainScope.launch {
                        when (it) {
                            is WebviewClientEvent.OnError -> {
                                trackPaywallError(
                                    it.webviewError,
                                    when (val e = it.webviewError) {
                                        is WebviewError.NetworkError ->
                                            listOf(e.url)

                                        is WebviewError.NoUrls ->
                                            emptyList()

                                        is WebviewError.MaxAttemptsReached ->
                                            e.urls

                                        is WebviewError.AllUrlsFailed -> e.urls
                                    },
                                )
                            }

                            is WebviewClientEvent.OnResourceError -> {
                                trackPaywallResourceError(
                                    it.webviewError,
                                    when (val e = it.webviewError) {
                                        is WebviewError.NetworkError ->
                                            e.url

                                        is WebviewError.NoUrls ->
                                            ""

                                        is WebviewError.MaxAttemptsReached ->
                                            e.urls.first()

                                        is WebviewError.AllUrlsFailed -> e.urls.first()
                                    },
                                )
                            }

                            is WebviewClientEvent.OnPageFinished -> {
                                onFinishedLoading?.invoke(it.url)
                            }

                            is WebviewClientEvent.LoadingFallback -> {
                                trackLoadFallback()
                            }
                        }
                    }
                }
        }
    }

    private fun trackLoadFallback() {
        mainScope.launch {
            delegate?.paywall?.webviewLoadingInfo?.failAt = Date()

            val paywallInfo = delegate?.info ?: return@launch

            val trackedEvent =
                InternalSuperwallEvent.PaywallWebviewLoad(
                    state =
                        InternalSuperwallEvent.PaywallWebviewLoad.State.Fallback,
                    paywallInfo = paywallInfo,
                )
            Superwall.instance.track(trackedEvent)
        }
    }

    private fun trackPaywallError(
        error: WebviewError,
        urls: List<String>,
    ) {
        mainScope.launch {
            delegate?.paywall?.webviewLoadingInfo?.failAt = Date()

            val paywallInfo = delegate?.info ?: return@launch

            val trackedEvent =
                InternalSuperwallEvent.PaywallWebviewLoad(
                    state =
                        InternalSuperwallEvent.PaywallWebviewLoad.State.Fail(
                            error,
                            urls,
                        ),
                    paywallInfo = paywallInfo,
                )
            Superwall.instance.track(trackedEvent)
        }
    }

    private fun trackPaywallResourceError(
        error: WebviewError,
        url: String,
    ) {
        mainScope.launch {
            val trackedEvent =
                InternalSuperwallEvent.PaywallResourceLoadFail(
                    url = url,
                    error = error.toString(),
                )
            Superwall.instance.track(trackedEvent)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (scrollEnabled || event.action != MotionEvent.ACTION_MOVE) {
            super.onTouchEvent(event)
        } else {
            val gesture = gestureDetector.onTouchEvent(event)
            if (!gesture) {
                super.onTouchEvent(event)
            } else {
                gesture
            }
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

internal fun webViewExists(): Boolean =
    try {
        WebView.getCurrentWebViewPackage() != null
    } catch (e: Throwable) {
        false
    }
