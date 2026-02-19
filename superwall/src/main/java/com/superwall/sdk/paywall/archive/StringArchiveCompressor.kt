package com.superwall.sdk.paywall.archive

import com.superwall.sdk.paywall.archive.models.ArchiveKeys.CONTENT_ID
import com.superwall.sdk.paywall.archive.models.ArchiveKeys.CONTENT_LOCATION
import com.superwall.sdk.paywall.archive.models.ArchiveKeys.CONTENT_TRANSFER_ENCODING
import com.superwall.sdk.paywall.archive.models.ArchiveKeys.CONTENT_TYPE
import com.superwall.sdk.paywall.archive.models.ArchiveKeys.ContentId
import com.superwall.sdk.paywall.archive.models.ArchiveKeys.TransferEncoding
import com.superwall.sdk.paywall.archive.models.ArchivePart
import com.superwall.sdk.paywall.archive.models.DecompressedWebArchive
import com.superwall.sdk.storage.toMD5
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

typealias CompressedWebArchive = String

interface ArchiveCompressor<Output, ArchiveType> {
    fun compressToArchive(
        url: String,
        parts: List<ArchivePart>,
    ): Output

    fun decompressFromArchive(archiveType: ArchiveType): Output
}

/*
 Creates a multipart HTML file from a list of ArchiveParts and vice-versa
*/
class StringArchiveCompressor(
    val encoder: ArchiveEncoder,
) {
    val se = StreamingBase64ArchiveEncoder()

    fun compressToArchive(
        url: String,
        parts: List<ArchivePart>,
    ): CompressedWebArchive = parts.createMultipartHTML(url, encoder)

    fun decompressArchive(archive: CompressedWebArchive): DecompressedWebArchive {
        // Extract the header and the remaining content
        val (headerParts, remaining) = archive.extractHeader()

        // Find the boundary delimiter
        val boundary =
            headerParts["boundary"]?.drop(1)?.dropLast(1)
                ?: return DecompressedWebArchive(headerParts, emptyList())

        // Split using the delimiter to get embedded documents
        // Note: the first two dashes are used to indicate the start of the boundary
        val parts = remaining.split("--$boundary")

        val archiveParts =
            parts
                // Filter out empty parts
                .filter { !it.isBlank() }
                .map {
                    // Extract map of content headers
                    val (headerParts, remaining) = it.extractHeader()
                    // Since some content can be text that is b64 encoded but not declared such
                    // we check if the content is base64 encoded by trying to decode it
                    val content =
                        try {
                            encoder.decodeDefault(remaining.trimEmptyLines().trim())
                        } catch (e: Throwable) {
                            remaining.trimEmptyLines().toByteArray(Charsets.UTF_8)
                        }
                    headerParts to content
                }.map {
                    val headers = it.first
                    val url = headers[CONTENT_LOCATION.key]?.trim() ?: ""
                    val mimeType = headers[CONTENT_TYPE.key]?.trim() ?: ""
                    val contentId = headers[CONTENT_ID.key]?.trim() ?: ""
                    if (contentId.contains(ContentId.MAIN_DOCUMENT.key)) {
                        ArchivePart.Document(
                            url = url,
                            mimeType = mimeType,
                            content = it.second,
                        )
                    } else {
                        ArchivePart.Resource(
                            url = url,
                            mimeType = mimeType,
                            content = it.second,
                        )
                    }
                }
        return DecompressedWebArchive(headerParts, archiveParts)
    }

    private val UTF8 = StandardCharsets.UTF_8
    private val CRLF = "\r\n".toByteArray(UTF8)
    private val DOUBLE_CRLF = "\r\n\r\n".toByteArray(UTF8)
    private val LF = "\n".toByteArray(UTF8)
    private val DASH_DASH = "--".toByteArray(UTF8)
    private val BOUNDARY_PREFIX = "--"

    // ---------- helpers ----------
    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** build a unique multipart boundary from URLs only (no allocations apart from MD5) */
    private fun buildBoundary(parts: List<ArchivePart>): String {
        val md = MessageDigest.getInstance("MD5")
        parts.forEach { md.update(it.url.toByteArray(UTF8)) }
        return md5Hex(md.digest())
    }

    /* ====================================================
     *                    PUBLIC API
     * ==================================================== */
}

private fun String.trimEmptyLines() =
    lines()
        .dropWhile { it.isEmpty() }
        .joinToString("\n")
        .trim()

// Creates multipart from a list of ArchiveParts and a base URL
fun List<ArchivePart>.createMultipartHTML(
    url: String,
    encoder: ArchiveEncoder,
): String {
    val archiveHash = joinToString(separator = "") { it.url }.toMD5()

    // Generated boundary - a separator for different parts in a MHTML file
    val boundary = "----MultipartBoundary--$archiveHash----"
    // Header for the MHTML file, mostly unimportant except
    // for the boundary and the content location
    val header =
        listOf(
            "From" to "<Saved by Superwall>",
            "MIME-Version" to "1.0",
            "Subject" to "Superwall Web Archive",
            "Snapshot-Content-Location" to url,
            "Content-Type" to "multipart/related;type=\"text/html\";boundary=\"$boundary\"",
        ).joinToString("\n") { "${it.first}: ${it.second}" }

    // Ensure document is first in the list
    val document = find { it is ArchivePart.Document }
    val resources = filter { it !is ArchivePart.Document }
    // Join document and resources as parts separated by boundary
    val combinedParts =
        (listOf(document) + resources)
            .filterNotNull()
            .map { it?.toMimePart(encoder) }
    // Return file as a combination of header and parts separated by boundary
    val mhtml =
        (listOf(header).plus(combinedParts)).joinToString(
            "\n\n--$boundary\n",
            postfix = "\n--$boundary",
        )

    return mhtml
}

// Extracts header parts of a mhtml and parses it into a map
fun String.extractHeader(): Pair<Map<String, String>, String> {
    val trimmed = lines().drop(lines().takeWhile { it.isBlank() }.size)
    val header =
        trimmed
            .takeWhile { it.isNotEmpty() }

    val headerParts =
        header
            .flatMap {
                it
                    .split(": ", ";", "=")
                    .chunked(2)
                    .map { it.first().trim() to it.last().trim() }
            }.toMap()

    val remaining = trimmed.drop(header.size).joinToString("\n")
    return headerParts to remaining
}

fun ArchivePart.toMimePart(encoder: ArchiveEncoder): String {
    val content =
        when (this) {
            is ArchivePart.Document ->
                encoder.encode(content)

            is ArchivePart.Resource -> {
                if (mimeType.contains("text")) {
                    encoder.encode(content)
                } else {
                    encoder.encode(content)
                }
            }
        }

    val header =
        listOf(
            CONTENT_TYPE to mimeType,
            CONTENT_TRANSFER_ENCODING to
                if (mimeType.contains("text")) {
                    TransferEncoding.QUOTED_PRINTABLE.key
                } else {
                    TransferEncoding.BASE64.key
                },
            CONTENT_LOCATION to url,
            CONTENT_ID to contentId,
        ).joinToString("") { "${it.first.key}: ${it.second}\n" }
    return "$header\n\n$content"
}
