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
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.text.drop
import kotlin.text.trim

class StreamArchiveCompressor(
    val encoder: ArchiveEncoder,
) {
    val se = StreamingBase64ArchiveEncoder()

    private val UTF8 = StandardCharsets.UTF_8
    private val CRLF = "\r\n".toByteArray(UTF8)
    private val DOUBLE_CRLF = "\r\n\r\n".toByteArray(UTF8)
    private val LF = "\n".toByteArray(UTF8)
    private val DASH_DASH = "--".toByteArray(UTF8)
    private val BOUNDARY_PREFIX = "--"
    private val md5 =
        MessageDigest
            .getInstance("MD5")

    // ---------- helpers ----------
    private fun md5Hex(bytes: ByteArray): String =
        md5
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** build a unique multipart boundary from URLs only (no allocations apart from MD5) */
    private fun buildBoundary(parts: List<ArchivePart>): String {
        val md = md5
        parts.forEach { md.update(it.url.toByteArray(UTF8)) }
        return md5Hex(md.digest())
    }

    /* ====================================================
     *                    PUBLIC API
     * ==================================================== */

    /** stream the complete archive to **any** `OutputStream` – no buffering */
    fun compressToStream(
        url: String,
        parts: List<ArchivePart>,
        out: OutputStream,
    ) {
        val boundary = buildBoundary(parts)
        val boundaryBytes = (BOUNDARY_PREFIX + boundary).toByteArray(UTF8)

        writeHeaders(out, url, boundary)

        // 1️⃣  document (if any)
        parts.firstOrNull { it is ArchivePart.Document }?.let { part ->
            out.write(CRLF)
            out.write(boundaryBytes)
            out.write(CRLF)
            writeMimeHeaders(out, part)
            se.streamEncode(part.content, out)
            out.write(CRLF)
        }

        // 2️⃣  all remaining resources
        for (part in parts) {
            if (part !is ArchivePart.Document) {
                out.write(CRLF)
                out.write(boundaryBytes)
                out.write(CRLF)
                writeMimeHeaders(out, part)
                se.streamEncode(part.content, out)
                out.write(CRLF)
            }
        }

        // closing boundary
        out.write(CRLF)
        out.write(boundaryBytes)
        out.write(DASH_DASH)
        out.write(CRLF)
        out.flush()
    }

    fun decompressArchiveStream(input: InputStream): DecompressedWebArchive {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val headers = mutableMapOf<String, String>()

        // --- Read top-level headers ---
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val colonIndex = line.indexOf(":")
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim().trimEnd(';', ' ')
                headers[key] = value
            }
        }

        val rawBoundary =
            headers["Content-Type"]
                ?.substringAfter("boundary=\"")
                ?.substringBefore("\"")
                ?: return DecompressedWebArchive(headers, emptyList())
        val boundaryLine = "--$rawBoundary"
        val boundaryEndLine = "--$rawBoundary--"

        val parts = mutableListOf<ArchivePart>()
        var line = reader.readLine()

        while (line != null) {
            // Look for next boundary
            while (line != null && line != boundaryLine && line != boundaryEndLine) {
                line = reader.readLine()
            }
            if (line == boundaryEndLine || line == null) break

            // --- Parse part headers ---
            val partHeaders = mutableMapOf<String, String>()
            while (true) {
                line = reader.readLine() ?: break
                if (line.isBlank()) break
                val colon = line.indexOf(":")
                if (colon > 0) {
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    partHeaders[key] = value
                }
            }

            val url = partHeaders["Content-Location"] ?: continue
            val mimeType = partHeaders["Content-Type"] ?: "application/octet-stream"
            val cid = partHeaders["Content-Id"] ?: ""
            val encoding = partHeaders["Content-Transfer-Encoding"]?.lowercase() ?: ""

            // --- Collect content until next boundary ---
            val contentBuffer = StringBuilder()
            while (true) {
                val nextLine = reader.readLine() ?: break
                if (nextLine == boundaryLine || nextLine == boundaryEndLine) {
                    line = nextLine
                    break
                }
                contentBuffer.appendLine(nextLine)
            }

            val raw = contentBuffer.toString()
            val content: ByteArray =
                try {
                    encoder.decodeDefault(raw.trim())
                } catch (e: Throwable) {
                    raw.toByteArray(Charsets.UTF_8) // might be binary-ish but works for text/html
                }

            val part =
                if (cid.contains(ContentId.MAIN_DOCUMENT.key)) {
                    ArchivePart.Document(url, mimeType, content)
                } else {
                    ArchivePart.Resource(url, mimeType, content)
                }
            parts.add(part)
        }

        return DecompressedWebArchive(headers, parts)
    }

    /* ====================================================
     *                    INTERNAL HELPERS
     * ==================================================== */

    // ---------- write-side helpers ----------
    private fun writeHeaders(
        out: OutputStream,
        url: String,
        boundary: String,
    ) {
        out.write("From: <Saved by Superwall>".toByteArray(UTF8))
        out.write(CRLF)
        out.write("MIME-Version: 1.0".toByteArray(UTF8))
        out.write(CRLF)
        out.write("Subject: Superwall Web Archive".toByteArray(UTF8))
        out.write(CRLF)
        out.write("Snapshot-Content-Location: ".toByteArray(UTF8))
        out.write(url.toByteArray(UTF8))
        out.write(CRLF)
        out.write(
            "Content-Type: multipart/related; type=\"text/html\"; boundary=\"".toByteArray(
                UTF8,
            ),
        )
        out.write(boundary.toByteArray(UTF8))
        out.write("\"".toByteArray(UTF8))
        out.write(CRLF)
    }

    private fun writeMimeHeaders(
        out: OutputStream,
        part: ArchivePart,
    ) {
        out.write("${CONTENT_TYPE.key}: ${part.mimeType}".toByteArray(UTF8))
        out.write(CRLF)
        val enc =
            if (part.mimeType.contains("text")) {
                TransferEncoding.QUOTED_PRINTABLE.key
            } else {
                TransferEncoding.BASE64.key
            }
        out.write("${CONTENT_TRANSFER_ENCODING.key}: $enc".toByteArray(UTF8))
        out.write(CRLF)
        out.write("${CONTENT_LOCATION.key}: ${part.url}".toByteArray(UTF8))
        out.write(CRLF)
        out.write("${CONTENT_ID.key}: ${part.contentId}".toByteArray(UTF8))
        out.write(DOUBLE_CRLF)
    }

    // ---------- header parsing ----------
    private fun parseHeaders(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val sep = ": ".toByteArray(UTF8)

        var i = from
        while (i < to) {
            val lineEnd = findSequence(bytes, LF, i).let { if (it < 0 || it > to) to else it }
            val slice = bytes.sliceArray(i until lineEnd)
            val colon = findSequence(slice, sep, 0)
            if (colon >= 0) {
                val key = String(slice, 0, colon, UTF8).trimEnd('\r')
                val value =
                    String(
                        slice,
                        colon + sep.size,
                        slice.size - colon - sep.size,
                        UTF8,
                    ).trimEnd('\r', ';', ' ')
                map[key] = value
            }
            i = lineEnd + 1
        }
        return map
    }

    // ---------- part extraction ----------
    private fun findSequence(
        hay: ByteArray,
        needle: ByteArray,
        start: Int,
        fail: IntArray = IntArray(0),
    ): Int {
        if (needle.isEmpty() || start >= hay.size) return -1

        // simple scan if no failure table
        if (fail.isEmpty()) {
            outer@ for (i in start..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        var j = 0
        for (i in start until hay.size) {
            while (j > 0 && hay[i] != needle[j]) j = fail[j - 1]
            if (hay[i] == needle[j]) j++
            if (j == needle.size) return i - j + 1
        }
        return -1
    }

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
                        .map { it.first().trim() to it.last() }
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
}
