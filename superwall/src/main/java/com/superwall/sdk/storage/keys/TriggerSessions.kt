package com.superwall.sdk.storage.keys

import com.superwall.sdk.analytics.model.TriggerSession
import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TriggerSessionsConfig: StorageConfig {
    override val key: String = "store.triggerSessions"
    override var directory: CacheDirectory = CacheDirectory.Cache
}


@Serializable
data class TriggerSessions(val sessions: List<TriggerSession>)

class TriggerSessionsManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): TriggerSessions? = mutex.withLock {
        this.cacheHelper.read(TriggerSessionsConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(triggerSessions: TriggerSessions) = mutex.withLock {
        this.cacheHelper.write(TriggerSessionsConfig, Json.encodeToString(triggerSessions).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(TriggerSessionsConfig)
    }
}
