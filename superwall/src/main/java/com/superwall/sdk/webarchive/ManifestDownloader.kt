package com.superwall.sdk.webarchive

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.config.WebArchiveManifest
import com.superwall.sdk.network.Network
import com.superwall.sdk.webarchive.archive.ArchivePart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.net.URL

/*
    Downloads WebArchive parts from a WebArchiveManifest.
    Careful, performs a lot of requests in parallel.
*/
class ManifestDownloader(
    private val coroutineScope: CoroutineScope,
    private val network: Network
) {

    /*
       Downloads the archive parts for a given manifest,
       starting with the main document, then it's relative dependencies
       and all other resources in parallel.
    */
    suspend fun downloadArchiveForManifest(
        manifest: WebArchiveManifest
    ): List<ArchivePart> {

        // Download the main document
        val mainDocumentUrl = manifest.document.url
        val mainDocument = network.fetchRemoteFile(mainDocumentUrl)
            .fold(onSuccess = { it }, onFailure = {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.webarchive,
                    "Failed to download main document: $mainDocumentUrl",
                    error = it,
                    info = mapOf("url" to mainDocumentUrl.toString())
                )
                throw it
            })

        // Discover relative resources in the document (those without a full path)
        val relativeUrls = discoverRelativeResources(mainDocument)
        val host = mainDocumentUrl.host
        val relativeParts = relativeUrls.map {
            WebArchiveManifest.Resource(
                url = URL("https://$host${it.key}"),
                mimeType = it.value
            )
        }
        val documentPart = ArchivePart.Document(
            url = mainDocumentUrl.toString(),
            content = mainDocument,
            mimeType = manifest.document.mimeType
        )

        //Combine all resources into a list of deferred jobs
        val jobs = (manifest.resources + relativeParts).map { resource ->
            //Creates download tasks
            coroutineScope.async {
                with(resource) {
                    network.fetchRemoteFile(url)
                        .fold(onSuccess = {
                            ArchivePart.Resource(
                                url = url.toString(),
                                mimeType = mimeType,
                                content = it
                            )
                        }, onFailure = {
                            Logger.debug(
                                logLevel = LogLevel.debug,
                                scope = LogScope.webarchive,
                                "Failed to download resource: $url",
                                error = it,
                                info = mapOf("url" to url.toString())
                            )
                            throw it
                        })
                }
            }
        }
        val parts = jobs.awaitAll() //Awaits the tasks in parallel
        return listOf(documentPart) + parts
    }

    /*
    Uses regex to match any relative resources in the main document that need
    to be downloaded for the core runtime. This matches all ="/runtime/" resources
    and returns a map of the relative path to the mime type judging by the extension
    (with a special case for javascript files).
    */
    private fun discoverRelativeResources(mainDocument: String): Map<String, String> {
        return Regex("=\"/runtime/[^\"]+\"")
            .findAll(mainDocument)
            .map {
                //Drop =" and " from the match
                it.value.drop(2).dropLast(1)
            }
            .map {
                val type = when (val extension = it.takeLastWhile { it != '.' }) {
                    "js" -> "javascript"
                    else -> extension
                }
                val mimeType = "text/$type"
                it to mimeType
            }
            .toMap()
    }
}
