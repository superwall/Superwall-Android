package com.superwall.sdk.webarchive.models

import com.superwall.sdk.webarchive.archive.ArchivePart

data class DecompressedWebArchive(
    val header: Map<String, String>,
    val content: List<ArchivePart>
)