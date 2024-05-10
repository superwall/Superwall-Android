package com.superwall.sdk.webarchive.archive

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.config.WebArchiveManifest
import com.superwall.sdk.network.Network
import com.superwall.sdk.storage.ReadWriteStorage
import com.superwall.sdk.storage.StoredWebArchive
import com.superwall.sdk.webarchive.ManifestDownloader
import com.superwall.sdk.webarchive.models.DecompressedWebArchive
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CachedArchiveLibraryTest {

    val paywallUrl = "paywallUrl"
    val paywallId = "paywallId"
    val storable = StoredWebArchive(paywallId)

    val storage = mockk<ReadWriteStorage>()
    val network = mockk<Network>()
    val manifestDownloader = mockk<ManifestDownloader>()
    val encoder = mockk<ArchiveEncoder>() {
        every { encode(any()) } returns "encoded"
        every { decodeDefault(any()) } returns "decoded".toByteArray()
        every { decode(any() as ByteArray) } returns "decoded".toByteArray()

    }

    val mockParts = listOf(
        ArchivePart.Document("url", "text/html", "content"),
        ArchivePart.Resource("url", "mimeType", "content")
    )

    @Test
    fun `downloadManifest should save archive to storage`() = runTest {
        val manifest = mockk<WebArchiveManifest>()
        val downloader = manifestDownloader
        val paywallUrl = "paywallUrl"
        every { storage.readFile(storable) } returns null
        every { storage.writeFile(storable, any()) } just Runs

        Given("A valid manifest that has not been saved before") {
            val archiveLibrary =
                CachedArchiveLibrary(storage, downloader, ArchiveCompressor(encoder))
            coEvery { downloader.downloadArchiveForManifest(manifest) } returns mockParts
            When("downloadManifest is called") {
                archiveLibrary.downloadManifest(paywallId, paywallUrl, manifest)
                Then("the archive should be saved to storage") {
                    verify { storage.writeFile(storable,any()) }
                }
            }
        }
    }

    @Test
    fun `downloadManifest should not save archive to storage if it already exists`() = runTest {
        val manifest = mockk<WebArchiveManifest>()
        val downloader = manifestDownloader
        every { storage.readFile(storable) } returns "file content"

        Given("A valid manifest that has been saved before") {
            val archiveLibrary =
                CachedArchiveLibrary(storage, downloader, ArchiveCompressor(encoder))
            When("downloadManifest is called") {
                archiveLibrary.downloadManifest(paywallId, paywallUrl, manifest)
                Then("the archive should not be downloaded or saved to storage") {
                    coVerify(exactly = 0) {
                        downloader.downloadArchiveForManifest(manifest)
                    }
                    coVerify(exactly = 0) {
                        storage.writeFile(storable,any())
                    }
                }
            }
        }
    }

    @Test
    fun `loadArchive should return the archive from storage if it exists`() = runTest {
        val archiveFile = "archive"
        val arch = DecompressedWebArchive(emptyMap(), emptyList())
        val compressor = mockk<ArchiveCompressor>() {
            coEvery { compressToArchive(paywallUrl, mockParts) } returns archiveFile
            coEvery { decompressArchive(archiveFile) } returns arch
        }
        every { storage.readFile(storable) } returns archiveFile

        Given("A paywall ID that exists in storage") {
            val archiveLibrary =
                CachedArchiveLibrary(storage, manifestDownloader,compressor )
            When("loadArchive is called") {
                val result = archiveLibrary.loadArchive(paywallId)
                Then("the archive should be returned from storage") {
                    assert(result.isSuccess)
                    assert(result.getOrNull() == arch)
                }
            }
        }
    }

    @Test
    fun `loadArchive should wait for the archive to be downloaded if it does not exist in storage`() =
        runTest {
            val archiveFile = "archive"
            val arch = DecompressedWebArchive(emptyMap(), emptyList())
            val manifest = mockk<WebArchiveManifest>()
            val compressor = mockk<ArchiveCompressor>() {
                coEvery { compressToArchive(paywallUrl, mockParts) } returns archiveFile
                coEvery { decompressArchive(archiveFile) } returns arch
            }
            every { storage.readFile(storable) } returns null

            coEvery { manifestDownloader.downloadArchiveForManifest(manifest) }.coAnswers {
                delay(100)
                mockParts
            }
            every { storage.readFile(storable) } returns archiveFile

            Given("A paywall ID that does not exist in storage") {
                val archiveLibrary =
                    CachedArchiveLibrary(storage, manifestDownloader, compressor)
                When("loadArchive is called before it is downloaded") {
                    launch { archiveLibrary.downloadManifest(paywallId, paywallUrl, manifest) }
                    val result = archiveLibrary.loadArchive(paywallId)

                    Then("the archive should be returned from storage") {
                        assert(result.isSuccess)
                        assert(result.getOrNull() == arch)
                    }
                }
            }
        }
}

