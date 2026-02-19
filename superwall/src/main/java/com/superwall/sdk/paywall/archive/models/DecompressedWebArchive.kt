package com.superwall.sdk.paywall.archive.models

data class DecompressedWebArchive(
    val header: Map<String, String>,
    val content: List<ArchivePart>,
)
