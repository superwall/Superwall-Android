package com.superwall.sdk.webarchive

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.config.WebArchiveManifest
import com.superwall.sdk.network.Network
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.URL

class ManifestDownloaderTest {

    private val docUrl = "https://example.com"
    private val resUrl = "https://example.com/image.jpg"
    private val relativeLink = "https://example.com/runtime/test.js"
    private val docContent = "document content"
    private val resContent = "resource content"
    private val relativeContent = "<script src='relative.js'></script>"
    private val network = mockk<Network> {
        coEvery { fetchRemoteFile(URL(docUrl)) } coAnswers { Result.success(docContent) }
        coEvery { fetchRemoteFile(URL(resUrl)) } coAnswers { Result.success(resContent) }
        coEvery { fetchRemoteFile(URL(relativeLink)) } coAnswers { Result.success(relativeContent) }
    }

    @Test
    fun `should download document and resources from manifest`() = runTest {
        Given("a manifest document and resources") {
            val manifest = WebArchiveManifest(
                WebArchiveManifest.Usage.ALWAYS,
                WebArchiveManifest.Document(
                    url = URL(docUrl),
                    mimeType = "text/html"
                ),
                listOf(
                    WebArchiveManifest.Resource(
                        url = URL(resUrl),
                        mimeType = "image/jpeg"
                    )
                )
            )
            When("the manifest is downloaded") {
                val downloader = ManifestDownloader(this@runTest, network)
                val res = downloader.downloadArchiveForManifest(manifest)
                Then("the document and resources should be downloaded") {
                    assert(res.size == 2)
                    assert(res[0].content == docContent)
                    assert(res[1].content == resContent)
                }
            }
        }
    }


    @Test
    fun `should download relative links when document contains them`() = runTest {
        Given("a manifest document and resources") {
            coEvery { network.fetchRemoteFile(URL(docUrl)) } coAnswers { Result.success("<a href=\"/runtime/test.js\"") }
            val manifest = WebArchiveManifest(
                WebArchiveManifest.Usage.ALWAYS,
                WebArchiveManifest.Document(
                    url = URL(docUrl),
                    mimeType = "text/html"
                ),
                listOf(
                    WebArchiveManifest.Resource(
                        url = URL(resUrl),
                        mimeType = "image/jpeg"
                    )
                )
            )
            When("the manifest is downloaded") {
                val downloader = ManifestDownloader(this@runTest, network)
                val res = downloader.downloadArchiveForManifest(manifest)
                Then("the document and resources should be downloaded") {
                    assert(res.size == 3)
                    assert(res[2].content == relativeContent)
                }
            }
        }
    }


}