package com.superwall.sdk.paywall.archival

import com.superwall.sdk.misc.RequestCoalescence
import com.superwall.sdk.misc.Result
import com.superwall.sdk.models.paywall.ArchivalManifest
import com.superwall.sdk.models.paywall.ArchivalManifestItem
import com.superwall.sdk.models.paywall.WebArchive
import com.superwall.sdk.models.paywall.WebArchiveResource
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
    private val requestCoalescence: RequestCoalescence<ArchivalManifestItem, WebArchiveResource> = RequestCoalescence(),
    private val archivalCoalescence: RequestCoalescence<ArchivalManifest, Result<WebArchive>> = RequestCoalescence()
) {

    fun archiveForManifestImmediately(manifest: ArchivalManifest): WebArchive? {
        val archiveFile = fsPath(forUrl = manifest.document.url)
        return if (archiveFile.exists()) {
            readArchiveFromFile(archiveFile)
        } else {
            null
        }
    }

    suspend fun archiveForManifest(manifest: ArchivalManifest): Result<WebArchive> =
        withContext(ioCoroutineDispatcher) {
            val archiveFile = fsPath(forUrl = manifest.document.url)
            if (archiveFile.exists()) {
                val archive = readArchiveFromFile(archiveFile)
                if (archive != null) {
                    Result.Success(archive)
                } else {
                    Result.Failure(Exception("Reading failed"))
                }
            } else {
                archivalCoalescence.get(input = manifest) {
                    createArchiveForManifest(manifest = it)
                }
            }
        }

    private suspend fun createArchiveForManifest(manifest: ArchivalManifest): Result<WebArchive> {
        return try {
            val archive = downloadManifest(manifest = manifest)
            val targetFile = fsPath(forUrl = manifest.document.url)
            writeArchiveToFile(archive = archive, file = targetFile)
            Result.Success(archive)
        } catch (exception: Exception) {
            Result.Failure(exception)
        }
    }

    // Consistent way to look up the appropriate directory for a given url
    private fun fsPath(forUrl: URL): File {
        val hostDashed = forUrl.host?.split(".")?.joinToString(separator = "-") ?: "unknown"
        var path = baseDirectory.resolve(hostDashed.replace(oldValue = "/", newValue = ""))
        forUrl.path.split("/").filter { it.isNotEmpty() }.forEach { item ->
            path = path.resolve(item)
        }
        path = File("${path.path}/cached.custom_web_archive")

        return path
    }

    private suspend fun downloadManifest(manifest: ArchivalManifest): WebArchive {
        val mainResourceDeferred = coroutineScope.async {
            requestCoalescence.get(manifest.document) {
                fetchDataForManifest(manifestItem = it)
            }
        }
        val subResourcesDeferred = manifest.resources.map { resource ->
            coroutineScope.async {
                requestCoalescence.get(resource) {
                    fetchDataForManifest(manifestItem = it)
                }
            }
        }
        val mainResource = mainResourceDeferred.await()
        val subResources = subResourcesDeferred.map { it.await() }
        return WebArchive(mainResource = mainResource, subResources = subResources)
    }

    private fun writeArchiveToFile(archive: WebArchive, file: File) {
        if (file.parentFile?.mkdirs() != false) {
            archive.writeToFile(file)
        } else {
            throw Exception("Cannot create directory")
        }
    }

    private fun readArchiveFromFile(file: File): WebArchive? {
        return try {
            file.readWebArchive()
        } catch (exception: Exception) {
            null
        }
    }

    private fun fetchDataForManifest(manifestItem: ArchivalManifestItem): WebArchiveResource {
        val connection = manifestItem.url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.useCaches = true
        val responseCode = connection.responseCode
        return if (responseCode == HttpURLConnection.HTTP_OK) {
            val data = connection.inputStream.readBytes()
            connection.disconnect()
            WebArchiveResource(
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
