package com.superwall.sdk.view

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.webkit.WebView
import com.superwall.sdk.api.PaywallMessage

class SWWebView(delegate: PaywallMessageDelegate, context: Context, attrs: AttributeSet?) : WebView(context, attrs), PaywallMessageDelegate {
    private val delegate: PaywallMessageDelegate = delegate

    init {
        setup()
    }

    private fun setup() {
        settings.javaScriptEnabled = true
        addJavascriptInterface(SWWebViewInterface(this, context), "SWAndroid")
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

        super.loadUrl(urlString)
    }

    override fun didReceiveMessage(message: PaywallMessage) {
        delegate.didReceiveMessage(message)
    }
}