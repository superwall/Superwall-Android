package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.misc.sdkVersion
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.storage.core_data.CoreDataManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import java.util.Date

open class Storage(
    context: Context,
    private val factory: Storage.Factory,
    /// The disk cache.
    private val cache: Cache = Cache(context = context),
    /// The interface that manages core data.
    val coreDataManager: CoreDataManager = CoreDataManager(context = context)
) : ReadWriteStorage {
    interface Factory: DeviceHelperFactory, HasExternalPurchaseControllerFactory {}

    /// The API key, set on configure.
    var apiKey: String = ""

    /// The API key for debugging, set when handling a deep link.
    var debugKey: String = ""

    /// Indicates whether first seen has been tracked.
    var didTrackFirstSeen: Boolean
        get() = runBlocking(queue) {
            _didTrackFirstSeen
        }
        set(value) {
            CoroutineScope(queue).launch {
                _didTrackFirstSeen = value
            }
        }
    private var _didTrackFirstSeen: Boolean = false

    /// Indicates whether first seen has been tracked.
    var didTrackFirstSession: Boolean
        get() = runBlocking(queue) {
            _didTrackFirstSession
        }
        set(value) {
            CoroutineScope(queue).launch {
                _didTrackFirstSession = value
            }
        }
    private var _didTrackFirstSession: Boolean = false

    /// Indicates whether static config hasn't been called before.
    ///
    /// Users upgrading from older SDK versions will not have called static config.
    /// This means that we'll need to wait for assignments before firing triggers.
    var neverCalledStaticConfig: Boolean = false

    /// The confirmed assignments for the user loaded from the cache.
    private var p_confirmedAssignments: Map<ExperimentID, Experiment.Variant>?
        get() = runBlocking(queue) {
            _confirmedAssignments
        }
        set(value) {
            CoroutineScope(queue).launch {
                _confirmedAssignments = value
            }
        }
    private var _confirmedAssignments: Map<ExperimentID, Experiment.Variant>? = null

    private val queue = newSingleThreadContext("com.superwall.storage")

    init {
        _didTrackFirstSeen = cache.read(DidTrackFirstSeen) == true

        // If we've already tracked firstSeen, then it can't be the first session. Useful for those upgrading.
        if (_didTrackFirstSeen) {
            _didTrackFirstSession = true
        } else {
            _didTrackFirstSession = cache.read(DidTrackFirstSession) == true
        }
    }

    fun configure(apiKey: String) {
        updateSdkVersion()
        this.apiKey = apiKey
    }

    /// Checks to see whether a user has upgraded from normal to static config.
    /// This blocks triggers until assignments is returned.
    private fun updateSdkVersion() {
        val actualSdkVersion = sdkVersion
        val previousSdkVersion = get(SdkVersion)

        if (actualSdkVersion != previousSdkVersion) {
            save(actualSdkVersion, SdkVersion)
        }

        if (previousSdkVersion == null) {
            neverCalledStaticConfig = true
        }
    }

    /// Clears data that is user specific.
    fun reset() {
        coreDataManager.deleteAllEntities()
        cache.cleanUserFiles()

        CoroutineScope(queue).launch {
            _confirmedAssignments = null
            _didTrackFirstSeen = false
        }
        recordFirstSeenTracked()
    }

    //region Custom

    /// Tracks and stores first seen for the user.
    fun recordFirstSeenTracked() {
        CoroutineScope(queue).launch {
            if (_didTrackFirstSeen) return@launch

            CoroutineScope(Dispatchers.IO).launch {
                Superwall.instance.track(InternalSuperwallEvent.FirstSeen())
            }

            save(true, DidTrackFirstSeen)
            _didTrackFirstSeen = true
        }
    }

    fun recordFirstSessionTracked() {
        CoroutineScope(queue).launch {
            if (_didTrackFirstSession) return@launch

            save(true, DidTrackFirstSession)
            _didTrackFirstSession = true
        }
    }

    /// Records the app install
    fun recordAppInstall(trackEvent: suspend (Trackable) -> TrackingResult) {
        val didTrackAppInstall = get(DidTrackAppInstall) ?: false
        if (didTrackAppInstall) {
            return
        }

        val hasExternalPurchaseController = factory.makeHasExternalPurchaseController()
        val deviceInfo = factory.makeDeviceInfo()

        CoroutineScope(Dispatchers.IO).launch {
            val event = InternalSuperwallEvent.AppInstall(
                appInstalledAtString = deviceInfo.appInstalledAtString,
                hasExternalPurchaseController = hasExternalPurchaseController
            )
            trackEvent(event)
        }
        save(true, DidTrackAppInstall)
    }

    open fun clearCachedSessionEvents() {
        cache.delete(TriggerSessions)
        cache.delete(Transactions)
    }

    fun trackPaywallOpen() {
        val totalPaywallViews = get(TotalPaywallViews) ?: 0
        save(totalPaywallViews + 1, TotalPaywallViews)
        save(Date(), LastPaywallView)
    }

    open fun saveConfirmedAssignments(assignments: Map<ExperimentID, Experiment.Variant>) {
        save(assignments, ConfirmedAssignments)
        p_confirmedAssignments = assignments
    }

    open fun getConfirmedAssignments(): Map<ExperimentID, Experiment.Variant> {
        p_confirmedAssignments?.let {
            return it
        } ?: run {
            val assignments = get(ConfirmedAssignments) ?: emptyMap()
            p_confirmedAssignments = assignments
            return assignments
        }
    }

    //endregion

    //region Cache Reading & Writing

    override fun <T> get(storable: Storable<T>): T? {
        return cache.read(storable)
    }

    override fun <T: Any> save(data: T, storable: Storable<T>) {
        cache.write(storable, data = data)
    }

    override fun writeFile(storable: Storable<*>, data: String) {
        cache.writeFile(storable, data)
    }

    override fun readFile(storable: Storable<*>): String? {
        return cache.readFile(storable)
    }

    //endregion
}


