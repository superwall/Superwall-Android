package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.misc.sdkVersion
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.sdk.storage.keys.*
import com.superwall.sdk.storage.memory.LRUCache
import com.superwall.sdk.storage.memory.PerpetualCache
import kotlinx.coroutines.*
import java.util.Date

open class Storage(
    private val context: Context,
    private val factory: DeviceInfoFactory,
) {

    public val coreDataManager: CoreDataManager = CoreDataManager()

    public val cache: Cache = Cache(context = context, config = CacheHelperConfiguration(
        memoryCache = LRUCache(PerpetualCache<String, ByteArray>(), 1000)
    ))

    var apiKey = ""
    var debugKey = ""
    private val queue = Dispatchers.IO

    @Volatile
    private var _didTrackFirstSeen: Boolean = false

    var didTrackFirstSeen: Boolean
        get() = _didTrackFirstSeen
        set(value) {
            _didTrackFirstSeen = value
        }

    @Volatile
    private var _didTrackFirstSession: Boolean = if (_didTrackFirstSeen) {
        true
    } else {
        cache.didTrackFirstSession.get()?.didTrackFirstSession ?: false
    }

    var didTrackFirstSession: Boolean
        get() = _didTrackFirstSession
        set(value) {
            _didTrackFirstSession = value
        }
//
    var neverCalledStaticConfig = false
//
    @Volatile
    private var _confirmedAssignments: Map<ExperimentID, Experiment.Variant>? = null

    private var confirmedAssignments: Map<ExperimentID, Experiment.Variant>?
        get() = _confirmedAssignments
        set(value) {
            _confirmedAssignments = value
        }
//
    suspend fun configure(apiKey: String) {
        updateSdkVersion()
        this.apiKey = apiKey
    }



    private suspend fun updateSdkVersion() {
        val actualSdkVersion = sdkVersion
        val previousSdkVersion = cache.sdkVersion.get()

        if (actualSdkVersion != previousSdkVersion?.sdkVersion) {
            cache.sdkVersion.set(SdkVersion(actualSdkVersion))
        }

        if (previousSdkVersion == null) {
            neverCalledStaticConfig = true
        }
    }
//
//    fun reset() {
//        coreDataManager.deleteAllEntities()
//        cache.cleanUserFiles()
//
//        withContext(queue) {
//            _confirmedAssignments = null
//            _didTrackFirstSeen = false
//        }
//        recordFirstSeenTracked()
//    }
//
    suspend fun recordFirstSeenTracked() {
        withContext(queue) {
            if (_didTrackFirstSeen) {
                return@withContext
            }

            Superwall.instance.track(InternalSuperwallEvent.FirstSeen())
            cache.didTrackFirstSeen.set(DidTrackFirstSeen(true))
            _didTrackFirstSeen = true
        }
    }

    suspend fun recordFirstSessionTracked() {
        withContext(queue) {
            if (_didTrackFirstSession) {
                return@withContext
            }

            cache.didTrackFirstSession.set(DidTrackFirstSession(true))
            _didTrackFirstSession = true
        }
    }

    fun recordAppInstall(
        trackEvent: suspend (Trackable) -> TrackingResult = { Superwall.instance.track(it) }
    ) {
        val didTrackAppInstall = cache.didTrackAppInstall.get()?.didTrackAppInstall ?: false
        if (didTrackAppInstall) {
            return
        }

//        withContext(queue) {
        CoroutineScope(Dispatchers.IO).launch {
            val deviceInfo = factory.makeDeviceInfo()
            val event =
                InternalSuperwallEvent.AppInstall(appInstalledAtString = deviceInfo.appInstalledAtString)
            trackEvent(event)
        }
        cache.didTrackAppInstall.set(DidTrackAppInstall(true))
    }

//    fun clearCachedSessionEvents() {
//        // TODO: implement
////        cache.delete(TriggerSessions::class)
////        cache.delete(Transactions::class)
//    }
//
//    fun trackPaywallOpen() {
//        val totalPaywallViews = get(TotalPaywallViews::class) ?: 0
//        save(totalPaywallViews + 1, TotalPaywallViews::class)
//        save(Date(), LastPaywallView::class)
//    }
//
    open suspend fun saveConfirmedAssignments(assignments: Map<ExperimentID, Experiment.Variant>) {
        cache.confirmedAssignments.set(ConfirmedAssignments(assignments))
        confirmedAssignments = assignments
    }
//
    open suspend fun getConfirmedAssignments(): Map<ExperimentID, Experiment.Variant> {
        if (confirmedAssignments != null) {
            return confirmedAssignments!!
        }
        val assignments = cache.confirmedAssignments.get()
        confirmedAssignments = assignments?.assignments ?: emptyMap()
        return confirmedAssignments!!
    }
}