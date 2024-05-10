package com.superwall.sdk.webarchive.client

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.paywall.vc.web_view.DefaultWebViewClient
import com.superwall.sdk.webarchive.archive.ArchiveEncoder
import com.superwall.sdk.webarchive.archive.ArchivePart
import com.superwall.sdk.webarchive.archive.Base64ArchiveEncoder
import com.superwall.sdk.webarchive.models.DecompressedWebArchive
import com.superwall.sdk.webarchive.models.MimeType

/*
* Routes requests coming to specific URLs to WebArchive files.
* */
open class ArchiveWebClient(
    private val archive: DecompressedWebArchive,
    private val encoder : ArchiveEncoder = Base64ArchiveEncoder(),
    onError: (WebResourceErrorCompat) -> Unit
) : DefaultWebViewClient(onError) {

    companion object {
        const val OVERRIDE_PATH = "https://appassets.androidplatform.net/assets/index.html"
    }

    val assetLoader = WebViewAssetLoader.Builder()
        // Requests coming towards these paths will be intercepted
        .addPathHandler("/assets/") { uri ->
            resolveUrlFromArchive(archive, uri)
        }
        .addPathHandler("/runtime/") { uri ->
            resolveUrlFromArchive(archive, uri)
        }
        .build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(Uri.parse(request.url.toString()))
    }


    override fun shouldInterceptRequest(
        view: WebView?,
        url: String
    ): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(Uri.parse(url))
    }

    /*
    Given an URL and an archive, resolves the URL by looking it up in the archive.
    A special case is when the URL is the index.html, in which case we look for the
    content type text/html and contentId=index.
    * */
    private fun resolveUrlFromArchive(
        archiveFile: DecompressedWebArchive,
        url: String
    ): WebResourceResponse {
        // Find the part that matches the requested url or the main document
        // Since they can be relative paths, it checks via .contains
        val part = archiveFile.content.find { part ->
            if (url.contains("index.html")) {
                part is ArchivePart.Document
            } else {
                part.url.contains(url)
            }
        }
        if(part == null) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.webarchive,
                message = "No part found for $url",
            )
            return WebResourceResponse(MimeType.HTML.toString(), "UTF-8", null)
        }
        val mimeType = part.mimeType
        return when (MimeType.fromString(mimeType).type) {
            "text" -> {
                // Respond with the content as text
                val response =
                    WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        part.content.byteInputStream()
                    )
                response
            }

            else -> {
                // Decode content as base64
                val unbased = encoder.decode(part.content.encodeToByteArray())
                val response = WebResourceResponse(mimeType.toString(), "UTF-8", unbased.inputStream())
                response
            }
        }
    }

}