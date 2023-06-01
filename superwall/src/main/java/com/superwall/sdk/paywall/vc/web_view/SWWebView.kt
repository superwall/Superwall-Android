package com.superwall.sdk.paywall.vc.web_view

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.ViewGroup.LayoutParams
import android.webkit.*
import androidx.core.view.ViewCompat
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.trigger_session.LoadState
import com.superwall.sdk.deprecated.PaywallMessage
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandler
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandlerDelegate
import com.superwall.sdk.view.SWWebViewInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

interface _SWWebViewDelegate {
    val paywallInfo: PaywallInfo
}

interface SWWebViewDelegate : _SWWebViewDelegate, PaywallMessageHandlerDelegate

class SWWebView(
    context: Context,
    private val sessionEventsManager: SessionEventsManager,
    private val messageHandler: PaywallMessageHandler
) : WebView(context) {
    var delegate: SWWebViewDelegate?  = null

    init {

        addJavascriptInterface(messageHandler, "SWAndroid")

        val webSettings = this.settings

        webSettings.javaScriptEnabled = true
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false

        // Enable inline media playback, requires API level 17
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.mediaPlaybackRequiresUserGesture = false
        }

        this.setBackgroundColor(Color.TRANSPARENT)

        this.webChromeClient = WebChromeClient()

        // Set a WebViewClient
        this.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return true // This will prevent the loading of URLs inside your WebView
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Do something when page loading finished
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                CoroutineScope(Dispatchers.Main).launch {
                    trackPaywallError()
                }
            }
        }
    }



    override fun loadUrl(url: String) {
        // Parse the url and add the query parameter
        val uri = Uri.parse(url)

        // Add a query parameter
        val builder = uri.buildUpon()
        builder.appendQueryParameter("transport", "android")
        builder.appendQueryParameter("debug", "true")
//        builder.appendQueryParameter("pjs", "http://10.0.2.2:4444/runtime/dev/entrypoint.js")
        val newUri = builder.build()

        // Use the new URL
        val urlString = newUri.toString()
        println("SWWebView.loadUrl: $urlString")

        super.loadUrl(urlString)
    }


    private suspend fun trackPaywallError() {
        delegate?.paywall?.webviewLoadingInfo?.failAt = Date()

        val paywallInfo = delegate?.paywallInfo ?: return

        sessionEventsManager.triggerSession.trackWebviewLoad(
            forPaywallId = paywallInfo.databaseId,
            state = LoadState.FAIL
        )

        val trackedEvent = InternalSuperwallEvent.PaywallWebviewLoad(
            state = InternalSuperwallEvent.PaywallWebviewLoad.State.Fail(),
            paywallInfo = paywallInfo
        )
        Superwall.instance.track(trackedEvent)
    }
}
