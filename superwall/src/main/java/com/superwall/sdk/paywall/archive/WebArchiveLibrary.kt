package com.superwall.sdk.paywall.archive

import com.superwall.sdk.models.paywall.WebArchiveManifest
import com.superwall.sdk.paywall.archive.models.DecompressedWebArchive

interface WebArchiveLibrary {
    suspend fun downloadManifest(
        paywallId: String,
        paywallUrl: String,
        manifest: WebArchiveManifest?,
    )

    fun checkIfArchived(paywallId: String): Boolean

    suspend fun loadArchive(paywallId: String): Result<DecompressedWebArchive>

    suspend fun awaitUntilQueueResolved(identifier: String)
}
