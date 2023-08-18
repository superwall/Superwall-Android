package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.storage.keys.*
import com.superwall.sdk.storage.memory.LRUCache
import com.superwall.sdk.storage.memory.PerpetualCache


interface StorageConfig {
    val key: String
    val directory: CacheDirectory
}


class Cache(
    var context: Context, var config: CacheHelperConfiguration = CacheHelperConfiguration(
        memoryCache = LRUCache(PerpetualCache<String, ByteArray>(), 1000)
    )
) {
    private val cacheHelper = CacheHelper(context, config)
    public val activeSubscriptionStatus = ActiveSubscriptionStatusManager(cacheHelper)
    public val aliasId = AliasIdManager(cacheHelper)
    public val appUserId = AppUserIdManager(cacheHelper)
    public val confirmedAssignments = ConfirmedAssignmentsManager(cacheHelper)
    public val didTrackAppInstall = DidTrackAppInstallManager(cacheHelper)
    public val didTrackFirstSeen = DidTrackFirstSeenManager(cacheHelper)
    public val didTrackFirstSession = DidTrackFirstSessionManager(cacheHelper)
    public val lastPaywallView = LastPaywallViewManager(cacheHelper)
    public val userAttributes = UserAttributesManager(cacheHelper)
    public val sdkVersion = SdkVersionManager(cacheHelper)


    suspend fun cleanUserFiles() {
        cacheHelper.cleanUserFiles()
    }
}
