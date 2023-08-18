package com.superwall.sdk.analytics.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.filterNotNull
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
    private val delegate: AppManagerDelegate
): BroadcastReceiver() {
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
        addActiveStateObservers()
        listenForAppSessionTimeout()
    }

    private fun addActiveStateObservers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(this, filter)
    }


    private fun willResign() {
        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.track(InternalSuperwallEvent.AppClose())
        }
        lastAppClose = Date()
        appSession.endAt = Date()
    }

    private fun didBecomeActive() {
        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.track(InternalSuperwallEvent.AppOpen())
        }
        sessionCouldRefresh()
    }

    private fun willTerminate() {
        appSession.endAt = Date()
    }


    fun listenForAppSessionTimeout() {
        CoroutineScope(Dispatchers.Main).launch {
            configManager.config
                .filterNotNull()
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
        // TODO: Figure out if this is the right dispatch queue
        GlobalScope.launch {
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
                Superwall.instance.track(InternalSuperwallEvent.SessionStart())
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

    override fun onReceive(context: Context?, intent: Intent?) {
       when(intent?.action)  {
           Intent.ACTION_SCREEN_OFF -> {
               // equivalent to "applicationWillResignActive"
               // your code here
               willResign()
           }
           Intent.ACTION_SCREEN_ON -> {
               // equivalent to "applicationDidBecomeActive"
               // your code here
                didBecomeActive()
           }
           Intent.ACTION_SHUTDOWN -> {
               // equivalent to "applicationWillTerminate"
               // your code here
                willTerminate()
           }
       }
    }
}