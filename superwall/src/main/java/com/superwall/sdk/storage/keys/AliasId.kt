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


object AliasIdConfig: StorageConfig {
    override val key: String = "store.aliasId"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}

@Serializable
data class AliasId(var aliasId: String)

class AliasIdManager(cacheHelper: CacheHelper) {
    private val cacheHelper = cacheHelper

    fun get(): AliasId? {
        this.cacheHelper.read(AliasIdConfig)?.let {
            return Json.decodeFromString(it.decodeToString())
        }
        return null
    }


    fun set(aliasId: AliasId) {
        this.cacheHelper.write(AliasIdConfig, Json.encodeToString(aliasId).toByteArray(Charsets.UTF_8))
    }

    fun delete() {
        this.cacheHelper.delete(AliasIdConfig)
    }
}
