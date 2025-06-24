package com.superwall.sdk.paywall.archive

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.paywall.archive.models.ArchivePart
import com.superwall.sdk.paywall.archive.models.DecompressedWebArchive
import com.superwall.sdk.paywall.archive.models.MimeType
import com.superwall.sdk.paywall.view.webview.DefaultWebviewClient

/*
* Routes requests coming to specific URLs to WebArchive files.
* */
internal class ArchiveWebClient(
    private val archive: DecompressedWebArchive,
    private val encoder: ArchiveEncoder = Base64ArchiveEncoder(),
    onError: (WebResourceErrorCompat) -> Unit,
) : DefaultWebviewClient(ioScope = IOScope()) {
    companion object {
        const val OVERRIDE_PATH = "https://appassets.androidplatform.net/assets/index.html"
    }

    val assetLoader =
        WebViewAssetLoader
            .Builder()
            // Requests coming towards these paths will be intercepted
            .addPathHandler("/assets/") { uri ->
                val url = resolveUrlFromArchive(archive, uri)
                url
            }.addPathHandler("/runtime/") { uri ->
                val url = resolveUrlFromArchive(archive, uri)
                url
            }.addPathHandler("/") { uri ->
                val url = resolveUrlFromArchive(archive, uri)
                url
            }.build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val res = assetLoader.shouldInterceptRequest(request.url.toString().toUri())
        return res
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        url: String,
    ): WebResourceResponse? {
        val res = assetLoader.shouldInterceptRequest(url.toUri())
        return res
    }

    /*
    Given an URL and an archive, resolves the URL by looking it up in the archive.
    A special case is when the URL is the index.html, in which case we look for the
    content type text/html and contentId=index.
     * */
    private fun resolveUrlFromArchive(
        archiveFile: DecompressedWebArchive,
        url: String,
    ): WebResourceResponse {
        // Find the part that matches the requested url or the main document
        // Since they can be relative paths, it checks via .contains
        val part =
            archiveFile.content.find { part ->
                if (url.contains("index.html")) {
                    part is ArchivePart.Document
                } else {
                    part.url.contains(url)
                }
            }
        if (part == null) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.webarchive,
                message = "No part found for $url",
            )
            return WebResourceResponse(
                MimeType.HTML.toString(),
                "UTF-8",
                404,
                "Not found",
                mutableMapOf(),
                "".toByteArray().inputStream(),
            )
        }
        val mimeType = part.mimeType
        return when (MimeType.fromString(mimeType).type) {
            "text" -> {
                // Respond with the content as text
                val response =
                    WebResourceResponse(
                        mimeType.toString(),
                        "UTF-8",
                        200,
                        "OK",
                        mutableMapOf<String, String>(),
                        part.content.inputStream(),
                    )
                response
            }

            else -> {
                // Decode content as base64
                return try {
                    WebResourceResponse(mimeType.toString(), "UTF-8", encoder.decode(part.content).inputStream())
                } catch (
                    e: Throwable,
                ) {
                    e.printStackTrace()
                    WebResourceResponse(mimeType.toString(), "UTF-8", ByteArray(0).inputStream())
                }
            }
        }
    }
}
