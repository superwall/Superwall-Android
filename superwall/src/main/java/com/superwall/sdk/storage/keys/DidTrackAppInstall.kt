package com.superwall.sdk.storage.keys


import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object DidTrackAppInstallConfig : StorageConfig {
    override val key: String = "store.didTrackAppInstall"
    override var directory: CacheDirectory = CacheDirectory.AppSpecificDocuments
}

@Serializable
data class DidTrackAppInstall(var didTrackAppInstall: Boolean)

class DidTrackAppInstallManager(cacheHelper: CacheHelper) {
    private val cacheHelper = cacheHelper

    fun get(): DidTrackAppInstall? {
        this.cacheHelper.read(DidTrackAppInstallConfig)?.let {
            return Json.decodeFromString(it.decodeToString())
        }
        return null
    }

    fun set(didTrackAppInstall: DidTrackAppInstall) {
        this.cacheHelper.write(
            DidTrackAppInstallConfig,
            Json.encodeToString(didTrackAppInstall).toByteArray(Charsets.UTF_8)
        )
    }

    fun delete() {
        this.cacheHelper.delete(DidTrackAppInstallConfig)
    }
}
