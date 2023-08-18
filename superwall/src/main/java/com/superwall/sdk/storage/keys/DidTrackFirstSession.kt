package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object DidTrackFirstSessionConfig: StorageConfig {
    override val key: String = "store.didTrackFirstSession"
    override var directory: CacheDirectory = CacheDirectory.AppSpecificDocuments
}

@Serializable
data class DidTrackFirstSession(var didTrackFirstSession: Boolean)

class DidTrackFirstSessionManager(cacheHelper: CacheHelper) {
    private val cacheHelper = cacheHelper


   fun get(): DidTrackFirstSession? {
       this.cacheHelper.read(DidTrackFirstSessionConfig)?.let {
           return Json.decodeFromString(it.decodeToString())
       }
       return null
   }

    fun set(didTrackFirstSession: DidTrackFirstSession) {
        this.cacheHelper.write(DidTrackFirstSessionConfig, Json.encodeToString(didTrackFirstSession).toByteArray(Charsets.UTF_8))
    }

    fun delete() {
        this.cacheHelper.delete(DidTrackFirstSessionConfig)
    }
}