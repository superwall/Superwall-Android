package com.superwall.sdk.webarchive.archive

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.storage.toMD5
import com.superwall.sdk.webarchive.models.ArchiveKeys
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ArchiveCompressorTest {

    private val encoder = mockk<ArchiveEncoder>()

    @Test
    fun `archive document part should map to mime part`() {
        Given("A document part") {
            val part = ArchivePart.Document("url", "text/html", "content")
            When("toMimePart is called") {
                val mimePart = part.toMimePart(encoder)
                Then("it should return the proper mimepart") {
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_TYPE}: text/html"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_TRANSFER_ENCODING}: quoted-printable"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_LOCATION}: url"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_ID}: <mainDocument>"))
                    assert(mimePart.contains("content"))
                }
            }
        }
    }


    @Test
    fun `archive resource part should map to mime part`() {
        Given("A resource part") {
            val part = ArchivePart.Resource("url", "text/css", "content")
            When("toMimePart is called") {
                val mimePart = part.toMimePart(encoder)
                Then("it should return the proper mimepart") {
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_TYPE}: text/css"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_TRANSFER_ENCODING}: quoted-printable"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_LOCATION}: url"))
                    assert(mimePart.contains("content"))
                }
            }
        }
    }

    @Test
    fun `archive resource part should map to b64 encoded mime`() {

        val content = "content"
        val decoded = "decoded".toByteArray()
        val result = "result"
        every { encoder.decodeDefault(content) } returns decoded
        every { encoder.encode(decoded) } returns result
        Given("A resource part") {
            val part = ArchivePart.Resource("url", "image/png", "content")
            When("toMimePart is called") {
                val mimePart = part.toMimePart(encoder)
                Then("it should return the proper mimepart") {
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_TYPE}: image/png"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_TRANSFER_ENCODING}: base64"))
                    assert(mimePart.contains("${ArchiveKeys.CONTENT_LOCATION}: url"))
                    assert(mimePart.contains(result))
                }
            }
        }
    }

    @Test
    fun `compressor should create a proper multipart file from parts`() {
        val parts = listOf(
            ArchivePart.Document("url", "text/html", "content"),
            ArchivePart.Resource("url", "mimeType", "content")
        )

        val content = "content"
        val decoded = "decoded".toByteArray()
        val result = "result"
        every { encoder.decodeDefault(content) } returns decoded
        every { encoder.encode(decoded) } returns result

        val archiveHash = parts.map { it.content }.joinToString(separator = "").toMD5()

        val boundary = "----MultipartBoundary--$archiveHash----"

        val header = listOf(
            "From" to "<Saved by Superwall>",
            "MIME-Version" to "1.0",
            "Subject" to "Superwall Web Archive",
            "Snapshot-${ArchiveKeys.CONTENT_LOCATION.key}" to "url",
            "${ArchiveKeys.CONTENT_TYPE}" to "multipart/related;type=\"text/html\";boundary=\"$boundary\"",
        ).joinToString("\n") { "${it.first}: ${it.second}" }


        Given("A list of archive parts") {
            When("createMultipartHTML is called") {
                val compressor = ArchiveCompressor(encoder)
                val multipart = compressor.compressToArchive("url", parts)
                Then("it should return the proper multipart file") {
                    assert(multipart.contains(header))
                    assert(multipart.contains("${ArchiveKeys.CONTENT_TYPE}: text/html"))
                    assert(multipart.contains("${ArchiveKeys.CONTENT_TRANSFER_ENCODING}: quoted-printable"))
                    assert(multipart.contains("${ArchiveKeys.CONTENT_LOCATION}: url"))
                    assert(multipart.contains("content"))
                    assert(multipart.contains("${ArchiveKeys.CONTENT_TYPE}: mimeType"))
                    assert(multipart.contains("${ArchiveKeys.CONTENT_TRANSFER_ENCODING}: quoted-printable"))
                    assert(multipart.contains("${ArchiveKeys.CONTENT_LOCATION}: url"))
                    assert(multipart.contains("content"))
                    assert(multipart.split(boundary).size == 5)
                }
            }
        }
    }

    @Test
    fun `compressor should be able to decompress its files back`(){
        val parts = listOf(
            ArchivePart.Document("url", "text/html", "content"),
            ArchivePart.Resource("url", "mimeType", "resource content"),
            ArchivePart.Resource("url", "text/javascript", "javascripthere")
        )


        val encoder = object : ArchiveEncoder {
            override fun encode(content: ByteArray) = content.toString(Charsets.UTF_8)
            override fun decode(string: ByteArray) = string

            override fun decodeDefault(string: String) = string.toByteArray()

        }
        val compressor = ArchiveCompressor(encoder)
        Given("A compressed archive file") {
        val archiveFile = compressor.compressToArchive("url", parts)
            When("decompressArchive is called") {
                val decompressed = compressor.decompressArchive(archiveFile)
                Then("it should return the proper decompressed archive") {
                    assert(decompressed.content.size == 3)
                    val doc = decompressed.content[0]
                    assert(doc.mimeType == "text/html")
                    assert(doc.url == "url")
                    assert(doc.content.trim() == "content")
                    val res = decompressed.content[1]
                    assert(res.mimeType == "mimeType")
                    assert(res.url == "url")
                    assert(res.content.trim() == "resource content")

                    val js = decompressed.content[2]
                    assert(js.mimeType == "text/javascript")
                    assert(js.url == "url")
                    assert(js.content.trim() == "javascripthere")

                }
            }
        }
    }
}