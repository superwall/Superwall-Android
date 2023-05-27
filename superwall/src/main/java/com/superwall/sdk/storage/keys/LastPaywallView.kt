package com.superwall.sdk.storage.keys

import com.superwall.sdk.models.serialization.DateSerializer
import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

object LastPaywallViewConfig: StorageConfig {
    override val key: String = "store.lastPaywallView"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}

@Serializable
data class LastPaywallView(
    @Serializable(with = DateSerializer::class)
    val date: Date
)

class LastPaywallViewManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): LastPaywallView? = mutex.withLock {
        this.cacheHelper.read(LastPaywallViewConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(lastPaywallView: LastPaywallView) = mutex.withLock {
        this.cacheHelper.write(LastPaywallViewConfig, Json.encodeToString(lastPaywallView).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(LastPaywallViewConfig)
    }
}
