package com.superwall.sdk.paywall.view.webview

import android.graphics.Bitmap
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal open class DefaultWebviewClient(
    private val forUrl: String = "",
    private val ioScope: CoroutineScope,
    private val onWebViewCrash: (view: WebView, RenderProcessGoneDetail) -> Unit = { v, d -> },
    private val localResourceHandler: LocalResourceHandler? = null,
    private val onPageStartedHook: (WebView) -> Unit = {},
) : WebViewClient() {
    val webviewClientEvents: MutableSharedFlow<WebviewClientEvent> =
        MutableSharedFlow(extraBufferCapacity = 10, replay = 2)

    // True once the currently loading page suffered a page-level failure (main frame
    // or its essential runtime bundle). Set synchronously on the WebViewClient callback
    // thread — before the corresponding OnError coroutine is launched — so consumers of
    // OnPageFinished can consult it without racing the async event; cleared when a new
    // page starts.
    @Volatile
    internal var hadMainFrameError = false
        private set

    // The paywall runtime JS bundle is essential — without it the page is broken —
    // so its failure is treated as page-level rather than as a mere resource error.
    protected fun isPageLevelFailure(request: WebResourceRequest?): Boolean =
        request?.isForMainFrame == true || request?.url?.toString()?.contains("runtime") == true

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean = true

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return super.shouldInterceptRequest(view, request)
        val handler = localResourceHandler ?: return super.shouldInterceptRequest(view, request)
        if (!handler.isLocalResourceUrl(url)) return super.shouldInterceptRequest(view, request)
        return handler.handleRequest(url)
    }

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        hadMainFrameError = false
        view?.let(onPageStartedHook)
    }

    override fun onPageFinished(
        view: WebView,
        url: String,
    ) {
        super.onPageFinished(view, url)
        ioScope.launch {
            webviewClientEvents.emit(WebviewClientEvent.OnPageFinished(url))
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        val requestUrl = request?.url?.toString()
        if (requestUrl?.contains("favicon.ico") == true) {
            return
        }
        val isPageLevel = isPageLevelFailure(request)
        if (isPageLevel) {
            hadMainFrameError = true
        }
        ioScope.launch {
            Logger.debug(
                LogLevel.error,
                LogScope.paywallView,
                "Paywall loading failed due to network error. Url: $requestUrl - Code: ${errorResponse?.statusCode} for ${errorResponse?.reasonPhrase}",
            )

            val error =
                WebviewError.NetworkError(
                    errorResponse?.statusCode ?: -1,
                    errorResponse?.let {
                        val body = it.data?.bufferedReader()?.use { it.readText() } ?: "Unknown"
                        "Error: ${errorResponse.reasonPhrase} -\n $body"
                    } ?: "Unknown error",
                    if (isPageLevel) forUrl else requestUrl ?: forUrl,
                )
            webviewClientEvents.emit(
                if (isPageLevel) {
                    WebviewClientEvent.OnError(error)
                } else {
                    WebviewClientEvent.OnResourceError(error)
                },
            )
        }
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        onWebViewCrash(view, detail)
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError,
    ) {
        val requestUrl = request?.url?.toString()
        val isPageLevel = isPageLevelFailure(request)
        if (isPageLevel) {
            hadMainFrameError = true
        }
        ioScope.launch {
            val (code, desc) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error.errorCode to error.description.toString()
                } else {
                    -1 to "Error description unavailable, Android API version < 23"
                }
            if (isPageLevel) {
                Logger.debug(
                    LogLevel.debug,
                    LogScope.paywallView,
                    "Paywall loading failed due to network error. Url: $requestUrl - Code: $code for $desc",
                )
                webviewClientEvents.emit(
                    WebviewClientEvent.OnError(
                        WebviewError.NetworkError(
                            code,
                            desc,
                            forUrl,
                        ),
                    ),
                )
            } else {
                webviewClientEvents.emit(
                    WebviewClientEvent.OnResourceError(
                        WebviewError.NetworkError(
                            code,
                            desc,
                            requestUrl ?: forUrl,
                        ),
                    ),
                )
            }
        }
    }
}
