package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SdkVersionConfig: StorageConfig {
    override val key: String = "store.sdkVersion"
    override var directory: CacheDirectory = CacheDirectory.AppSpecificDocuments
}

@Serializable
data class SdkVersion(var sdkVersion: String)

class SdkVersionManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): SdkVersion? = mutex.withLock {
        this.cacheHelper.read(SdkVersionConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(sdkVersion: SdkVersion) = mutex.withLock {
        this.cacheHelper.write(SdkVersionConfig, Json.encodeToString(sdkVersion).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(SdkVersionConfig)
    }
}
