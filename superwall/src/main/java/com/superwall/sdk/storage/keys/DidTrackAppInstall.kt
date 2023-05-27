package com.superwall.sdk.storage.keys


import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object DidTrackAppInstallConfig: StorageConfig {
    override val key: String = "store.didTrackAppInstall"
    override var directory: CacheDirectory = CacheDirectory.AppSpecificDocuments
}

@Serializable
data class DidTrackAppInstall(var didTrackAppInstall: Boolean)

class DidTrackAppInstallManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): DidTrackAppInstall? = mutex.withLock {
        this.cacheHelper.read(DidTrackAppInstallConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(didTrackAppInstall: DidTrackAppInstall) = mutex.withLock {
        this.cacheHelper.write(DidTrackAppInstallConfig, Json.encodeToString(didTrackAppInstall).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(DidTrackAppInstallConfig)
    }
}
