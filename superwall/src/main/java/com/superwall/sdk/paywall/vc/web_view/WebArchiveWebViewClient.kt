package com.superwall.sdk.paywall.vc.web_view

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.superwall.sdk.models.paywall.WebArchive
import com.superwall.sdk.models.paywall.WebArchiveResource

open class WebArchiveWebViewClient(
    val webArchive: WebArchive
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        return if (url.contains(webArchive.mainResource.url.toString())) {
            webArchive.mainResource.toWebResourceResponse()
        } else {
            //if no subresource for url exists the method returns null and the subresource is loaded from network
            webArchive.subResources.find { it.url.toString() == url }?.toWebResourceResponse()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return true
    }

    private fun WebArchiveResource.toWebResourceResponse() =
        WebResourceResponse(mimeType, Charsets.UTF_8.name(), data.inputStream())

}