package com.superwall.sdk.webarchive.archive

import com.superwall.sdk.models.config.WebArchiveManifest
import com.superwall.sdk.webarchive.models.DecompressedWebArchive

interface WebArchiveLibrary {

    suspend fun downloadManifest(paywallId: String,
                                 paywallUrl: String,
                                 archiveFile: WebArchiveManifest)
    fun checkIfArchived(paywallId: String) : Boolean

    suspend fun loadArchive(paywallId: String) : Result<DecompressedWebArchive>

}