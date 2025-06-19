package com.superwall.sdk.paywall.archive

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.paywall.archive.models.ArchivePart
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class CompressedWebArchiveTest {
    private lateinit var encoder: ArchiveEncoder
    private lateinit var compressor: ArchiveCompressor

    @Before
    fun setup() {
        encoder = mockk()
        compressor = ArchiveCompressor(encoder)
    }

    @Test
    fun test_compress_to_archive_valid() {
        Given("a document and resources") {
            val documentContent = "<html><body>Hello</body></html>".toByteArray()
            val resourceContent = "body { color: red; }".toByteArray()
            val document =
                ArchivePart.Document(
                    url = "https://example.com/index.html",
                    mimeType = "text/html",
                    content = documentContent,
                )
            val resource =
                ArchivePart.Resource(
                    url = "https://example.com/style.css",
                    mimeType = "text/css",
                    content = resourceContent,
                )
            every { encoder.encode(documentContent) } returns "ENCODED_HTML"
            every { encoder.encode(resourceContent) } returns "ENCODED_CSS"

            val parts = listOf(document, resource)
            val url = "https://example.com/index.html"

            When("compressing") {
                val archive = compressor.compressToArchive(url, parts)

                Then("output is valid multipart HTML") {
                    // Check boundary
                    val boundaryRegex = Regex("----MultipartBoundary--[a-f0-9]+----")
                    assert(boundaryRegex.containsMatchIn(archive))
                    // Check headers
                    assert(archive.contains("MIME-Version: 1.0"))
                    assert(archive.contains("Content-Type: multipart/related"))
                    // Check encoded content
                    assert(archive.contains("ENCODED_HTML"))
                    assert(archive.contains("ENCODED_CSS"))
                    // Document should come before resource
                    val docIndex = archive.indexOf("ENCODED_HTML")
                    val resIndex = archive.indexOf("ENCODED_CSS")
                    assert(docIndex in 0 until resIndex)
                }
            }
        }
    }

    @Test
    fun test_compress_to_archive_empty() {
        Given("an empty list") {
            val parts = emptyList<ArchivePart>()
            val url = "https://example.com/index.html"

            When("compressing") {
                val archive = compressor.compressToArchive(url, parts)

                Then("output is a valid (empty) archive") {
                    // Check boundary
                    val boundaryRegex = Regex("----MultipartBoundary--[a-f0-9]+----")
                    assert(boundaryRegex.containsMatchIn(archive))
                    // Check headers
                    assert(archive.contains("MIME-Version: 1.0"))
                    assert(archive.contains("Content-Type: multipart/related"))
                    // Should not contain any encoded content
                    assert(!archive.contains("ENCODED_HTML"))
                    assert(!archive.contains("ENCODED_CSS"))
                }
            }
        }
    }

    @Test
    fun test_decompress_archive_valid() {
        Given("a valid archive (doc + resources)") {
            val documentContent = "<html><body>Hello</body></html>".toByteArray()
            val resourceContent = "body { color: red; }".toByteArray()
            val document =
                ArchivePart.Document(
                    url = "https://example.com/index.html",
                    mimeType = "text/html",
                    content = documentContent,
                )
            val resource =
                ArchivePart.Resource(
                    url = "https://example.com/style.css",
                    mimeType = "text/css",
                    content = resourceContent,
                )
            every { encoder.encode(documentContent) } returns "ENCODED_HTML"
            every { encoder.encode(resourceContent) } returns "ENCODED_CSS"
            every { encoder.decodeDefault("ENCODED_HTML") } returns documentContent
            every { encoder.decodeDefault("ENCODED_CSS") } returns resourceContent

            val parts = listOf(document, resource)
            val url = "https://example.com/index.html"
            val archive = compressor.compressToArchive(url, parts)

            When("decompressing") {
                val decompressed = compressor.decompressArchive(archive)

                Then("output matches original parts") {
                    assert(decompressed.content.size == 2)
                    val doc = decompressed.content.find { it is ArchivePart.Document } as? ArchivePart.Document
                    val res = decompressed.content.find { it is ArchivePart.Resource } as? ArchivePart.Resource
                    assert(doc != null)
                    assert(res != null)
                    assert(doc.url == document.url)
                    assert(doc.mimeType == document.mimeType)
                    assert(doc.content.contentEquals(document.content))
                    assert(res.url == resource.url)
                    assert(res.mimeType == resource.mimeType)
                    assert(res.content.contentEquals(resource.content))
                }
            }
        }
    }

    @Test
    fun test_decompress_archive_only_document() {
        Given("an archive with only a document") {
            val documentContent = "<html><body>Only Doc</body></html>".toByteArray()
            val document =
                ArchivePart.Document(
                    url = "https://example.com/index.html",
                    mimeType = "text/html",
                    content = documentContent,
                )
            every { encoder.encode(documentContent) } returns "ENCODED_ONLY_DOC"
            every { encoder.decodeDefault("ENCODED_ONLY_DOC") } returns documentContent

            val parts = listOf(document)
            val url = "https://example.com/index.html"
            val archive = compressor.compressToArchive(url, parts)

            When("decompressing") {
                val decompressed = compressor.decompressArchive(archive)

                Then("output contains only the document") {
                    assert(decompressed.content.size == 1)
                    val doc = decompressed.content.first() as? ArchivePart.Document
                    assert(doc != null)
                    assert(doc.url == document.url)
                    assert(doc.mimeType == document.mimeType)
                    assert(doc.content.contentEquals(document.content))
                }
            }
        }
    }

    @Test
    fun test_decompress_archive_various_encodings() {
        Given("archive with base64 and quoted-printable") {
            val textContent = "<html>Text</html>".toByteArray()
            val binaryContent = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val document =
                ArchivePart.Document(
                    url = "https://example.com/index.html",
                    mimeType = "text/html",
                    content = textContent,
                )
            val resource =
                ArchivePart.Resource(
                    url = "https://example.com/image.png",
                    mimeType = "image/png",
                    content = binaryContent,
                )
            every { encoder.encode(textContent) } returns "QUOTED_PRINTABLE_TEXT"
            every { encoder.encode(binaryContent) } returns "BASE64_BINARY"
            every { encoder.decodeDefault("QUOTED_PRINTABLE_TEXT") } returns textContent
            every { encoder.decodeDefault("BASE64_BINARY") } returns binaryContent

            val parts = listOf(document, resource)
            val url = "https://example.com/index.html"
            val archive = compressor.compressToArchive(url, parts)

            When("decompressing") {
                val decompressed = compressor.decompressArchive(archive)

                Then("content is decoded") {
                    assert(decompressed.content.size == 2)
                    val doc = decompressed.content.find { it is ArchivePart.Document } as? ArchivePart.Document
                    val res = decompressed.content.find { it is ArchivePart.Resource } as? ArchivePart.Resource
                    assert(doc != null)
                    assert(res != null)
                    assert(doc.content.contentEquals(textContent))
                    assert(res.content.contentEquals(binaryContent))
                }
            }
        }
    }

    @Test
    fun test_decompress_archive_malformed() {
        Given("malformed archive") {
            val malformedArchive =
                """
                From: <Saved by Superwall>
                MIME-Version: 1.0
                Subject: Superwall Web Archive
                Snapshot-Content-Location: https://example.com/index.html
                Content-Type: multipart/related;type=\"text/html\"
                
                --MISSING-BOUNDARY
                Content-Type: text/html
                Content-Location: https://example.com/index.html
                Content-Id: <main>
                
                <html>Malformed</html>
                """.trimIndent()

            When("decompressing") {
                val decompressed = compressor.decompressArchive(malformedArchive)

                Then("throws or handles gracefully") {
                    // Should not throw, should return empty parts
                    assert(decompressed.content.isEmpty())
                }
            }
        }
    }

    @Test
    fun test_create_multipart_html_order_and_boundary() {
        Given("parts") {
            val documentContent = "<html>Order</html>".toByteArray()
            val resourceContent = "body { color: blue; }".toByteArray()
            val document =
                ArchivePart.Document(
                    url = "https://example.com/index.html",
                    mimeType = "text/html",
                    content = documentContent,
                )
            val resource =
                ArchivePart.Resource(
                    url = "https://example.com/style.css",
                    mimeType = "text/css",
                    content = resourceContent,
                )
            every { encoder.encode(documentContent) } returns "DOC_CONTENT"
            every { encoder.encode(resourceContent) } returns "RES_CONTENT"

            val parts = listOf(resource, document) // Intentionally out of order
            val url = "https://example.com/index.html"

            When("creating multipart HTML") {
                val archive = parts.createMultipartHTML(url, encoder)

                Then("doc is first and boundary is correct") {
                    // Check boundary
                    val boundaryRegex = Regex("----MultipartBoundary--[a-f0-9]+----")
                    assert(boundaryRegex.containsMatchIn(archive))
                    // Document should come before resource
                    val docIndex = archive.indexOf("DOC_CONTENT")
                    val resIndex = archive.indexOf("RES_CONTENT")
                    assert(docIndex in 0 until resIndex)
                }
            }
        }
    }

    @Test
    fun test_extract_header_correctness() {
        Given("string with headers and content") {
            val headerString =
                """
                Content-Type: text/html
                Content-Location: https://example.com/index.html
                Content-Id: <main>
                
                <html>HeaderTest</html>
                """.trimIndent()

            When("extracting") {
                val (headers, content) = headerString.extractHeader()

                Then("correct map and content") {
                    assert(headers["Content-Type"] == "text/html")
                    assert(headers["Content-Location"] == "https://example.com/index.html")
                    assert(headers["Content-Id"] == "<main>")
                    assert(content.trim() == "<html>HeaderTest</html>")
                }
            }
        }
    }

    @Test
    fun test_extract_header_with_whitespace() {
        Given("string with whitespace") {
            val headerString =
                """
                Content-Type: text/html  
                Content-Location:   https://example.com/index.html
                Content-Id:    <main>
                
                
                <html>WhitespaceTest</html>
                """.trimIndent()

            When("extracting") {
                val (headers, content) = headerString.extractHeader()

                Then("parses correctly") {
                    assert(headers["Content-Type"] == "text/html")
                    assert(headers["Content-Location"] == "https://example.com/index.html")
                    assert(headers["Content-Id"] == "<main>")
                    assert(content.trim() == "<html>WhitespaceTest</html>")
                }
            }
        }
    }

    @Test
    fun test_to_mime_part_document() {
        Given("document part") {
            val documentContent = "<html>MimeDoc</html>".toByteArray()
            val document =
                ArchivePart.Document(
                    url = "https://example.com/index.html",
                    mimeType = "text/html",
                    content = documentContent,
                )
            every { encoder.encode(documentContent) } returns "ENCODED_DOC"

            When("converting") {
                val mimePart = document.toMimePart(encoder)

                Then("correct headers/content") {
                    assert(mimePart.contains("Content-Type: text/html"))
                    assert(mimePart.contains("Content-Location: https://example.com/index.html"))
                    assert(mimePart.contains("Content-Id: <main>"))
                    assert(mimePart.contains("ENCODED_DOC"))
                }
            }
        }
    }

    @Test
    fun test_to_mime_part_resource_text() {
        Given("resource part (text)") {
            val resourceContent = "body { color: green; }".toByteArray()
            val resource =
                ArchivePart.Resource(
                    url = "https://example.com/style.css",
                    mimeType = "text/css",
                    content = resourceContent,
                )
            every { encoder.encode(resourceContent) } returns "ENCODED_TEXT_RES"

            When("converting") {
                val mimePart = resource.toMimePart(encoder)

                Then("quoted-printable encoding") {
                    assert(mimePart.contains("Content-Type: text/css"))
                    assert(mimePart.contains("Content-Location: https://example.com/style.css"))
                    assert(mimePart.contains("Content-Id: <resource>"))
                    assert(mimePart.contains("ENCODED_TEXT_RES"))
                    assert(mimePart.contains("Content-Transfer-Encoding: quoted-printable"))
                }
            }
        }
    }

    @Test
    fun test_to_mime_part_resource_binary() {
        Given("resource part (binary)") {
            val resourceContent = byteArrayOf(0x10, 0x20, 0x30, 0x40)
            val resource =
                ArchivePart.Resource(
                    url = "https://example.com/image.png",
                    mimeType = "image/png",
                    content = resourceContent,
                )
            every { encoder.encode(resourceContent) } returns "ENCODED_BINARY_RES"

            When("converting") {
                val mimePart = resource.toMimePart(encoder)

                Then("base64 encoding") {
                    assert(mimePart.contains("Content-Type: image/png"))
                    assert(mimePart.contains("Content-Location: https://example.com/image.png"))
                    assert(mimePart.contains("Content-Id: <resource>"))
                    assert(mimePart.contains("ENCODED_BINARY_RES"))
                    assert(mimePart.contains("Content-Transfer-Encoding: base64"))
                }
            }
        }
    }
} 
