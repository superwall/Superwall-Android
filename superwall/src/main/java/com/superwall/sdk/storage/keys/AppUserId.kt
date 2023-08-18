package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object AppUserIdConfig : StorageConfig {
    override val key: String = "store.appUserId"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}

@Serializable
data class AppUserId(var appUserId: String)

class AppUserIdManager(cacheHelper: CacheHelper) {
    private val cacheHelper = cacheHelper


    fun get(): AppUserId? {
        this.cacheHelper.read(AppUserIdConfig)?.let {
            return Json.decodeFromString(it.decodeToString())
        }
        return null
    }

    fun set(appUserId: AppUserId) {
        this.cacheHelper.write(
            AppUserIdConfig,
            Json.encodeToString(appUserId).toByteArray(Charsets.UTF_8)
        )
    }

    fun delete() {
        this.cacheHelper.delete(AppUserIdConfig)
    }
}
