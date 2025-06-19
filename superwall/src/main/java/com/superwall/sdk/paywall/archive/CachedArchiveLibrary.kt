package com.superwall.sdk.paywall.archive

import android.util.Log
import com.superwall.sdk.models.paywall.WebArchiveManifest
import com.superwall.sdk.paywall.archive.models.ArchivePart
import com.superwall.sdk.paywall.archive.models.DecompressedWebArchive
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredWebArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Manages WebArchives, downloading and saving them to file cache.
 */
class CachedArchiveLibrary(
    private val storage: Storage,
    private val manifestDownloader: ManifestDownloader,
    private val archiveCompressor: ArchiveCompressor,
) : WebArchiveLibrary {
    // Queue of paywallIds that are currently being downloaded
    private val archiveQueue = MutableStateFlow(listOf<String>())

    override suspend fun downloadManifest(
        paywallId: String,
        paywallUrl: String,
        manifest: WebArchiveManifest?,
    ) {
        // Return if the paywall is already archived or waiting to be archived
        if ( // checkIfArchived(paywallId) ||
            archiveQueue.value.contains(paywallId)
        ) {
            return
        }

        archiveQueue.update {
            it + paywallId
        }

        Log.e("DL", "Manifest starting for $paywallId")
        val archive =
            manifestDownloader.downloadArchiveForManifest(
                paywallId,
                manifest ?: WebArchiveManifest(
                    WebArchiveManifest.Usage.ALWAYS,
                    WebArchiveManifest.Document(paywallUrl, "text/html"),
                    emptyList(),
                ),
            )
        Log.e("DL", "Manifest done for $paywallId")
        val storable = StoredWebArchive(paywallId)

        storage
            .getFileStream(
                storable = storable,
            ).use {
                archiveCompressor.compressToStream(paywallUrl, archive, it)
            }
        archiveQueue.update {
            it.minus(paywallId)
        }
    }

    // Check if the paywall is archived already
    override fun checkIfArchived(paywallId: String): Boolean {
        val archive = StoredWebArchive(paywallId)
        return storage.readFile(archive) != null
    }

    // Load the archive from cache, if it does not exist, throw an exception
    override suspend fun loadArchive(paywallId: String): Result<DecompressedWebArchive> {
        // If doesn't exist, await until it's downloaded
        if (!checkIfArchived(paywallId)) {
            awaitUntilQueueResolved(paywallId)
        }
        val storeable = StoredWebArchive(paywallId)
        val fromCache = storage.readFile(storeable)
        return if (fromCache != null) {
            val decompressed = archiveCompressor.decompressArchive(fromCache)
            Result.success(decompressed)
        } else {
            Result.failure(NoSuchElementException("Paywall $paywallId does not exist in cache"))
        }
    }

    // Checks and awaits if the paywall is in queue, otherwise returns immediately
    override suspend fun awaitUntilQueueResolved(paywallId: String) {
        archiveQueue.first {
            !it.contains(paywallId)
        }
    }

    fun getArchiveInputStream(
        url: String,
        parts: List<ArchivePart>,
    ): java.io.InputStream {
        val pipeIn = java.io.PipedInputStream()
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        Thread {
            try {
                archiveCompressor.compressToStream(url, parts, pipeOut)
            } catch (e: Throwable) {
                // If an error occurs, close the pipe
                pipeIn.close()
            } finally {
                pipeOut.close()
            }
        }.apply {
            isDaemon = true
            start()
        }
        return pipeIn
    }
}
