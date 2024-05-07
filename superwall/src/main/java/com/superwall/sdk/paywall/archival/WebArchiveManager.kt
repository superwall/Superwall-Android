package com.superwall.sdk.paywall.archival

import com.superwall.sdk.misc.RequestCoalescence
import com.superwall.sdk.misc.Result
import com.superwall.sdk.models.paywall.ArchivalManifest
import com.superwall.sdk.models.paywall.ArchivalManifestDownloaded
import com.superwall.sdk.models.paywall.ArchivalManifestItem
import com.superwall.sdk.models.paywall.ArchivalManifestItemDownloaded
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebArchiveManager(
    private val baseDirectory: File,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val ioCoroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestCoalescence: RequestCoalescence<ArchivalManifestItem, ArchivalManifestItemDownloaded> = RequestCoalescence(),
    private val archivalCoalescence: RequestCoalescence<ArchivalManifest, Result<File>> = RequestCoalescence()
) {

    fun archiveForManifestImmediately(manifest: ArchivalManifest): File? {
        val archiveFile = fsPath(forUrl = manifest.document.url)
        return if (archiveFile.exists()) {
            archiveFile
        } else {
            null
        }
    }

    suspend fun archiveForManifest(manifest: ArchivalManifest): Result<File> =
        withContext(ioCoroutineDispatcher) {
            val archiveFile = fsPath(forUrl = manifest.document.url)
            if (archiveFile.exists()) {
                return@withContext Result.Success(archiveFile)
            }
            archivalCoalescence.get(input = manifest) {
                createArchiveForManifest(manifest = it)
            }
        }

    private suspend fun createArchiveForManifest(manifest: ArchivalManifest): Result<File> {
        return try {
            val downloadedManifest = downloadManifest(manifest = manifest)
            val targetFile = fsPath(forUrl = manifest.document.url)
            writeManifest(manifest = downloadedManifest, file = targetFile)
            Result.Success(targetFile)
        } catch (exception: Exception) {
            Result.Failure(exception)
        }
    }

    // Consistent way to look up the appropriate directory
    // for a given url
    private fun fsPath(forUrl: URL): File {
        val hostDashed = forUrl.host?.split(".")?.joinToString(separator = "-") ?: "unknown"
        var path = baseDirectory.resolve(hostDashed.replace(oldValue = "/", newValue = ""))
        forUrl.path.split("/").filter { it.isNotEmpty() }.forEach { item ->
            path = path.resolve(item)
        }
        path = File("${path.path}/cached.mht")

        return path
    }

    private suspend fun downloadManifest(manifest: ArchivalManifest): ArchivalManifestDownloaded {
        val documentDeferred = coroutineScope.async {
            requestCoalescence.get(manifest.document) {
                fetchDataForManifest(manifestItem = it)
            }
        }
        val itemsDeferred = manifest.resources.map { resource ->
            coroutineScope.async {
                requestCoalescence.get(resource) {
                    fetchDataForManifest(manifestItem = it)
                }
            }
        }
        val document = documentDeferred.await()
        val items = itemsDeferred.map { it.await() }
        return ArchivalManifestDownloaded(document = document, items = items)
    }

    // Helper to write manifest
    private fun writeManifest(manifest: ArchivalManifestDownloaded, file: File) {
        if (file.parentFile?.mkdirs() != false) {
            file.writeText(WebArchiveEncoder().encode(manifest))
        } else {
            throw Exception("Cannot create directory")
        }
    }

    // Helper to actually fetch the manifest
    private fun fetchDataForManifest(manifestItem: ArchivalManifestItem): ArchivalManifestItemDownloaded {
        val connection = manifestItem.url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.useCaches = true
        val responseCode = connection.responseCode
        return if (responseCode == HttpURLConnection.HTTP_OK) {
            val data = connection.inputStream.readBytes()
            connection.disconnect()
            ArchivalManifestItemDownloaded(
                url = manifestItem.url,
                mimeType = manifestItem.mimeType,
                data = data,
            )
        } else {
            connection.disconnect()
            throw Exception("Invalid response for resource: ${manifestItem.url}")
        }
    }
}
