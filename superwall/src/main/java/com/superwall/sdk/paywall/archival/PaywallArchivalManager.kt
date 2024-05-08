package com.superwall.sdk.paywall.archival

import com.superwall.sdk.misc.Result
import com.superwall.sdk.models.paywall.ArchivalManifestUsage
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.WebArchive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class PaywallArchivalManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val ioCoroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var webArchiveManager: WebArchiveManager? = null,
    baseDirectory: File? = null,
) {

    init {
        if (webArchiveManager == null && baseDirectory != null) {
            webArchiveManager = WebArchiveManager(baseDirectory = baseDirectory.resolve("paywalls"))
        }
    }

    fun preloadArchiveAndShouldSkipViewControllerCache(paywall: Paywall): Boolean {
        webArchiveManager?.let { webArchiveManager ->
            if (paywall.manifest != null) {
                if (paywall.manifest.use == ArchivalManifestUsage.NEVER) {
                    return false
                }
                coroutineScope.launch(ioCoroutineDispatcher) {
                    webArchiveManager.archiveForManifest(manifest = paywall.manifest)
                }
                return true
            }
        }
        return false
    }

    // If we should be really aggressive and wait for the archival to finish
    // before we load
    fun shouldWaitForWebArchiveToLoad(paywall: Paywall): Boolean {
        return webArchiveManager != null && paywall.manifest?.use == ArchivalManifestUsage.ALWAYS
    }

    // We'll try to see if it's cached, if not we'll just
    // skip it and fall back to the normal method of loading
    fun cachedArchiveForPaywallImmediately(paywall: Paywall): WebArchive? {
        webArchiveManager?.let { webArchiveManager ->
            if (paywall.manifest != null) {
                if (paywall.manifest.use == ArchivalManifestUsage.NEVER) {
                    return null
                }
                return webArchiveManager.archiveForManifestImmediately(manifest = paywall.manifest)
            }
        }
        return null
    }

    suspend fun cachedArchiveForPaywall(paywall: Paywall): WebArchive? {
        webArchiveManager?.let { webArchiveManager ->
            if (paywall.manifest != null) {
                if (paywall.manifest.use == ArchivalManifestUsage.NEVER) {
                    return null
                }
                val result = webArchiveManager.archiveForManifest(manifest = paywall.manifest)
                return when (result) {
                    is Result.Success -> result.value
                    is Result.Failure -> null
                }
            }
        }
        return null
    }

}