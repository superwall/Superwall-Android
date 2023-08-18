package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ActiveSubscriptionStatusConfig: StorageConfig {
    override val key: String = "store.subscriptionStatus"
    override var directory: CacheDirectory = CacheDirectory.AppSpecificDocuments
}

// Assuming SubscriptionStatus is a serializable data class. Replace with actual definition.
// TODO: Replace with actual definition.
@Serializable
data class SubscriptionStatus(val status: String) {
    val description: String get()  {
        return status
    }
}

@Serializable
data class ActiveSubscriptionStatus(var subscriptionStatus: SubscriptionStatus)

class ActiveSubscriptionStatusManager(cacheHelper: CacheHelper) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): ActiveSubscriptionStatus? = mutex.withLock {
        this.cacheHelper.read(ActiveSubscriptionStatusConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(activeSubscriptionStatus: ActiveSubscriptionStatus) = mutex.withLock {
        this.cacheHelper.write(ActiveSubscriptionStatusConfig, Json.encodeToString(activeSubscriptionStatus).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(ActiveSubscriptionStatusConfig)
    }
}
