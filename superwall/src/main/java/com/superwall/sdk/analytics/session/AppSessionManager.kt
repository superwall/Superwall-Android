package com.superwall.sdk.analytics.session

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.*

interface AppManagerDelegate {
    suspend fun didUpdateAppSession(appSession: AppSession)
}

//
class AppSessionManager(
    private val context: Context,
    private val configManager: ConfigManager,
    private val storage: Storage,
    private val delegate: AppSessionManager.Factory
) : DefaultLifecycleObserver {
    interface Factory: AppManagerDelegate, DeviceHelperFactory {}

    var appSessionTimeout: Long? = null

    var appSession = AppSession()
        set(value) {
            field = value
            CoroutineScope(Dispatchers.Main).launch {
                delegate.didUpdateAppSession(value)
            }
        }
    private var lastAppClose: Date? = null
    private var didTrackAppLaunch = false

    init {
        listenForAppSessionTimeout()
    }

    // Called when the app goes to the foreground
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.track(InternalSuperwallEvent.AppOpen())
        }
        sessionCouldRefresh()
    }

    // Called when the app goes to the background
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.track(InternalSuperwallEvent.AppClose())
        }
        lastAppClose = Date()
        appSession.endAt = Date()
    }

    private fun willTerminate() {
        appSession.endAt = Date()
    }


    fun listenForAppSessionTimeout() {
        CoroutineScope(Dispatchers.Main).launch {
            configManager.configState
                .mapNotNull { it.getSuccess()?.getConfig() }
                .collect { config ->
                    appSessionTimeout = config.appSessionTimeout

                    // Account for fact that dev may have delayed the init of Superwall
                    // such that applicationDidBecomeActive() doesn't activate.
                    if (!didTrackAppLaunch) {
                        sessionCouldRefresh()
                    }
                }
        }
    }

    private fun sessionCouldRefresh() {
        detectNewSession()
        trackAppLaunch()
        CoroutineScope(Dispatchers.IO).launch {
            storage.recordFirstSeenTracked()
        }
    }

    private fun detectNewSession() {
        val didStartNewSession = AppSessionLogic.didStartNewSession(
            lastAppClose,
            appSessionTimeout
        )

        if (didStartNewSession) {
            appSession = AppSession()
            CoroutineScope(Dispatchers.IO).launch {
                val attributes = delegate.makeSessionDeviceAttributes()
                Superwall.instance.track(InternalSuperwallEvent.SessionStart())
                Superwall.instance.track(InternalSuperwallEvent.DeviceAttributes(
                    deviceAttributes = attributes
                ))
            }
        } else {
            appSession.endAt = null
        }
    }

    //
    private fun trackAppLaunch() {
        if (didTrackAppLaunch) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.track(InternalSuperwallEvent.AppLaunch())
        }
        didTrackAppLaunch = true
    }
}
