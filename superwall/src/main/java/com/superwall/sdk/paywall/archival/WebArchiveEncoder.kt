package com.superwall.sdk.paywall.archival

import com.superwall.sdk.models.paywall.ArchivalManifestDownloaded
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

class WebArchiveEncoder {

    private val boundary: String = "----MultipartBoundary--${UUID.randomUUID()}--"

    fun encode(manifest: ArchivalManifestDownloaded): String {

        val mhtmlContent = buildString {
            appendLine("From: <Saved by Superwall>")
            appendLine("Snapshot-Content-Location: ${manifest.document.url}")
            appendLine("Subject: Paywall")
            appendLine("Date: ${currentDateTimeFormatted()}")
            appendLine("MIME-Version: 1.0")
            appendLine("Content-Type: multipart/related;")
            appendLine("\ttype=\"text/html\";")
            appendLine("\tboundary=\"$boundary\"")
            appendNewLine()
            appendNewLine()
            appendBoundary()
            appendLine("Content-Type: text/html")
            appendLine("Content-Transfer-Encoding: base64")
            appendLine("Content-Location: ${manifest.document.url}")
            appendNewLine()
            appendLine(Base64.getEncoder().encodeToString(manifest.document.data))
            appendNewLine()

            for (item in manifest.items) {
                appendBoundary()
                appendLine("Content-Type: ${item.mimeType}")
                appendLine("Content-Transfer-Encoding: base64")
                appendLine("Content-Location: ${item.url}")
                appendNewLine()
                appendLine(Base64.getEncoder().encodeToString(item.data))
                appendNewLine()
            }

            appendClosingBoundary()
        }

        return mhtmlContent
    }

    private fun StringBuilder.appendBoundary() = appendLine("--$boundary")

    private fun StringBuilder.appendClosingBoundary() = appendLine("--$boundary--")

    private fun StringBuilder.appendLine(value: String) = append(value).appendNewLine()

    private fun StringBuilder.appendNewLine() = append("\r\n")

    private fun currentDateTimeFormatted() =
        ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH))
}