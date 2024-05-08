package com.superwall.sdk.paywall.archival

import com.superwall.sdk.models.paywall.WebArchive
import com.superwall.sdk.models.paywall.WebArchiveResource
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.URL
import java.util.Base64

fun WebArchive.writeToFile(file: File) {
    BufferedWriter(FileWriter(file)).use { writer ->
        mainResource.write(writer)
        subResources.forEach { resource ->
            resource.write(writer)
        }
    }
}

private fun WebArchiveResource.write(bufferedWriter: BufferedWriter) {
    with(bufferedWriter) {
        appendLine(url.toString())
        appendLine(mimeType)
        appendLine(Base64.getEncoder().encodeToString(data))
    }
}

fun File.readWebArchive(): WebArchive? {
    return BufferedReader(FileReader(this)).use { reader ->
        val mainResource = reader.readWebArchiveResource()
        if (mainResource != null) {
            val subResources = mutableListOf<WebArchiveResource>()
            var resource = reader.readWebArchiveResource()
            while (resource != null) {
                subResources.add(resource)
                resource = reader.readWebArchiveResource()
            }
            WebArchive(
                mainResource = mainResource,
                subResources = subResources
            )
        } else {
            null
        }
    }
}

private fun BufferedReader.readWebArchiveResource(): WebArchiveResource? {
    val url = readLine()
    val mimeType = readLine()
    val data = readLine()
    return if (url != null && mimeType != null && data != null) {
        WebArchiveResource(
            url = URL(url),
            mimeType = mimeType,
            data = Base64.getDecoder().decode(data)

        )
    } else {
        null
    }
}
