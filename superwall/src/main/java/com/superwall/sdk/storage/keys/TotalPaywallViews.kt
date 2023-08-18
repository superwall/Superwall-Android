package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TotalPaywallViewsConfig: StorageConfig {
    override val key: String = "store.totalPaywallViews"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}

@Serializable
data class TotalPaywallViews(var views: Int)

class TotalPaywallViewsManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): TotalPaywallViews? = mutex.withLock {
        this.cacheHelper.read(TotalPaywallViewsConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(totalPaywallViews: TotalPaywallViews) = mutex.withLock {
        this.cacheHelper.write(TotalPaywallViewsConfig, Json.encodeToString(totalPaywallViews).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(TotalPaywallViewsConfig)
    }
}
