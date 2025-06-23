package com.superwall.sdk.paywall.archive

import androidx.core.net.toUri
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.map
import com.superwall.sdk.misc.onError
import com.superwall.sdk.models.paywall.WebArchiveManifest
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.archive.models.ArchivePart
import com.superwall.sdk.paywall.archive.models.MimeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.net.URL
import java.util.concurrent.Executors

/*
    Downloads WebArchive parts from a WebArchiveManifest.
    Careful, performs a lot of requests in parallel.
*/
class ManifestDownloader(
    private val coroutineScope: CoroutineScope,
    private val network: Network,
) {
    /*
       Downloads the archive parts for a given manifest,
       starting with the main document, then it's relative dependencies
       and all other resources in parallel.
     */
    suspend fun downloadArchiveForManifest(
        id: String,
        manifest: WebArchiveManifest,
    ): List<ArchivePart> {
        // Download the main document
        val mainDocumentUrl = manifest.document.url
        val mainDocument =
            network
                .fetchRemoteFile(mainDocumentUrl.toUri(), id)
                .onError {
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.webarchive,
                        "Failed to download main document: $mainDocumentUrl",
                        error = it,
                        info = mapOf("url" to mainDocumentUrl.toString()),
                    )
                    throw it
                }.getSuccess()!!

        val mainDocumentString = mainDocument.content.toString(Charsets.UTF_8)
        val relativeUrls = discoverRelativeResources(mainDocumentString)
        val host = URL(mainDocumentUrl)
        val favicoUrl =
            WebArchiveManifest.Resource(
                "https://${host.host}/favicon.ico",
                MimeType.FAVICON.toString(),
            )
        val relativeParts =
            relativeUrls.map {
                WebArchiveManifest.Resource(
                    url = "https://${host.host}${if (it.key.startsWith("/")) it.key else "/${it.key}"}",
                    mimeType = it.value,
                )
            }
        val absoluteParts =
            discoverAbsoluteResources(mainDocumentString).map {
                WebArchiveManifest.Resource(
                    it.key,
                    it.value,
                )
            }

        val documentPart =
            ArchivePart.Document(
                url = mainDocumentUrl.toString(),
                content = mainDocument.content,
                mimeType = "text/html",
            )

        val foundParts = (absoluteParts + relativeParts + manifest.resources + favicoUrl).toSet()
        val dispatcher =
            Executors.newFixedThreadPool(32).asCoroutineDispatcher().limitedParallelism(10)
        val xope = IOScope(dispatcher)
        // Combine all resources into a list of deferred jobs
        val jobs =
            (foundParts).distinctBy { it.url }.map { resource ->
                // Creates download tasks
                xope.async {
                    with(resource) {
                        network
                            .fetchRemoteFile(resource.url.toUri(), id)
                            .map {
                                ArchivePart.Resource(
                                    url = url.toString(),
                                    mimeType = mimeType,
                                    content = it.content,
                                )
                            }.onError {
                                Logger.debug(
                                    logLevel = LogLevel.debug,
                                    scope = LogScope.webarchive,
                                    "Failed to download resource: $url",
                                    error = it,
                                    info = mapOf("url" to url.toString()),
                                )
                                throw it
                            }.getSuccess()!!
                    }
                }
            }
        val relativeUrlsOnly = (relativeParts + favicoUrl).map { it.url }
        val parts =
            jobs.chunked(5).flatMap { it.awaitAll() }.map {
                if (relativeUrlsOnly.contains(it.url)) {
                    if (it.url.contains("favicon.ico")) {
                        "favicon.ico"
                    }
                    it.copy(url = it.url.removePrefix("https://${host.host}"))
                } else {
                    it
                }
            }

        return parts + documentPart
    }

    /*
        Uses regex to match any absolute resources in the main document that need
        to be downloaded for the core runtime. This matches all ="http://" and ="https://" resources
        and returns a map of the absolute URL to the mime type judging by the extension
        (with a special case for javascript files).
     */
    fun discoverAbsoluteResources(mainDocument: String): Map<String, String> =
        Regex("(?:=\"|\":\"|\bsrc\\s*=\\s*\")https?://[^\"]+\"")
            .findAll(mainDocument)
            .map {
                // Extract just the URL part by finding the https:// portion
                val match = it.value
                val urlStart =
                    match.indexOf("https://").takeIf { it != -1 }
                        ?: match.indexOf("http://")
                match.substring(urlStart, match.length - 1) // Remove trailing quote
            }.filter {
                it.removePrefix("https://").contains("/") && !it.contains("w3.org")
            }.map {
                val type =
                    when (val extension = it.takeLastWhile { it != '.' }) {
                        "js" -> "javascript"
                        else -> extension
                    }
                val mimeType = "text/$type"
                it to mimeType
            }.toMap()

    /*
    Uses regex to match any relative resources in the main document that need
    to be downloaded for the core runtime. This matches all ="/runtime/" resources
    and returns a map of the relative path to the mime type judging by the extension
    (with a special case for javascript files).
     */
    fun discoverRelativeResources(mainDocument: String): Map<String, String> {
        val allMatches = mutableListOf<String>()

        // Pattern 1: Attribute assignments with double quotes: attr="/runtime/..." or attr="../..."
        val attrDoubleQuotePattern =
            Regex("""(?:=|href\s*=\s*)"(/runtime/[^"]+|\.\./[^"]+|build/[^"]+)"""")
        allMatches.addAll(
            attrDoubleQuotePattern
                .findAll(mainDocument)
                .map { it.groupValues[1] }
                .toList(),
        )

        // Pattern 2: Attribute assignments with single quotes: attr='/runtime/...' or attr='../..'
        val attrSingleQuotePattern =
            Regex("""(?:=|href\s*=\s*)'(/runtime/[^']+|\.\./[^']+|build/[^']+)'""")
        allMatches.addAll(
            attrSingleQuotePattern
                .findAll(mainDocument)
                .map { it.groupValues[1] }
                .toList(),
        )

        // Pattern 3: JSON properties with double quotes: "key":"/runtime/..." or "key":"../..."
        val jsonPattern = Regex(""""[^"]*":\s*"(/runtime/[^"]+|\.\./[^"]+|build/[^"]+)"""")
        allMatches.addAll(
            jsonPattern
                .findAll(mainDocument)
                .map { it.groupValues[1] }
                .toList(),
        )

        return allMatches
            .map { url ->
                val extension = url.substringAfterLast('.', "")
                val type =
                    when (extension) {
                        "js" -> "javascript"
                        "" -> "plain" // fallback for URLs without extension
                        else -> extension
                    }
                val mimeType = "text/$type"
                val url =
                    if (url.startsWith("../")) {
                        url.drop(3)
                    } else {
                        url
                    }
                url to mimeType
            }.toMap()
    }
}
