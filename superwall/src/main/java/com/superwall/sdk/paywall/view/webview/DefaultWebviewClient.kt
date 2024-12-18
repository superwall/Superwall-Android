package com.superwall.sdk.paywall.view.webview

import android.graphics.Bitmap
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal open class DefaultWebviewClient(
    private val forUrl: String = "",
    private val ioScope: CoroutineScope,
    private val onWebViewCrash: (RenderProcessGoneDetail) -> Unit = { },
) : WebViewClient() {
    val webviewClientEvents: MutableSharedFlow<WebviewClientEvent> =
        MutableSharedFlow(extraBufferCapacity = 4)

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean = true

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(
        view: WebView,
        url: String,
    ) {
        ioScope.launch {
            webviewClientEvents.emit(WebviewClientEvent.OnPageFinished(url))
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        val requestUrl = request?.url.toString()
        if (requestUrl.contains("favicon.ico")) {
            return
        }
        ioScope.launch {
            webviewClientEvents.emit(
                WebviewClientEvent.OnResourceError(
                    WebviewError.NetworkError(
                        errorResponse?.statusCode ?: -1,
                        errorResponse?.let {
                            val body = it.data?.bufferedReader()?.use { it.readText() } ?: "Unknown"
                            "Error: ${errorResponse.reasonPhrase} -\n $body"
                        } ?: "Unknown error",
                        forUrl,
                    ),
                ),
            )
        }
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        onWebViewCrash(detail)
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError,
    ) {
        ioScope.launch {
            webviewClientEvents.emit(
                WebviewClientEvent.OnError(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        WebviewError.NetworkError(
                            error.errorCode,
                            error.description.toString(),
                            forUrl,
                        )
                    } else {
                        WebviewError.NetworkError(
                            -1,
                            "Error description unavailable, Android API version < 23",
                            forUrl,
                        )
                    },
                ),
            )
        }
    }
}
