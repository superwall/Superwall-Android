package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object DidTrackFirstSeenConfig : StorageConfig {
    override val key: String = "store.didTrackFirstSeen.v2"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}

@Serializable
data class DidTrackFirstSeen(var didTrackFirstSeen: Boolean)

class DidTrackFirstSeenManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    fun get(): DidTrackFirstSeen? {
        this.cacheHelper.read(DidTrackFirstSeenConfig)?.let {
            return Json.decodeFromString(it.decodeToString())
        }
        return null
    }

    fun set(didTrackFirstSeen: DidTrackFirstSeen) {
        this.cacheHelper.write(
            DidTrackFirstSeenConfig,
            Json.encodeToString(didTrackFirstSeen).toByteArray(Charsets.UTF_8)
        )
    }

    fun delete() {
        this.cacheHelper.delete(DidTrackFirstSeenConfig)
    }
}
