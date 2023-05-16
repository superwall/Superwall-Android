package com.superwall.sdk.analytics.session
//
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.flow.collect
//import kotlinx.coroutines.flow.filterNotNull
//import java.util.*
//
//interface AppManagerDelegate {
//    suspend fun didUpdateAppSession(appSession: AppSession)
//}
//
//class AppSessionManager(
//    private val configManager: ConfigManager,
//    private val storage: Storage,
//    private val delegate: AppManagerDelegate
//) {
//    var appSessionTimeout: Long? = null
//
//    private var appSession = AppSession()
//        set(value) {
//            field = value
//            CoroutineScope(Dispatchers.Main).launch {
//                delegate.didUpdateAppSession(value)
//            }
//        }
//    private var lastAppClose: Date? = null
//    private var didTrackAppLaunch = false
//
//    init {
//        addActiveStateObservers()
//        listenForAppSessionTimeout()
//    }
//
//    private fun addActiveStateObservers() {
//        ApplicationObserver.willResignActive = {
//            CoroutineScope(Dispatchers.IO).launch {
//                Superwall.shared.track(InternalSuperwallEvent.AppClose())
//            }
//            lastAppClose = Date()
//            appSession.endAt = Date()
//        }
//
//        ApplicationObserver.didBecomeActive = {
//            CoroutineScope(Dispatchers.IO).launch {
//                Superwall.shared.track(InternalSuperwallEvent.AppOpen())
//            }
//            sessionCouldRefresh()
//        }
//
//        ApplicationObserver.willTerminate = {
//            appSession.endAt = Date()
//        }
//    }
//
//    fun listenForAppSessionTimeout() {
//        CoroutineScope(Dispatchers.Main).launch {
//            configManager.config
//                .filterNotNull()
//                .collect { config ->
//                    appSessionTimeout = config.appSessionTimeout
//
//                    // Account for fact that dev may have delayed the init of Superwall
//                    // such that applicationDidBecomeActive() doesn't activate.
//                    if (!didTrackAppLaunch) {
//                        sessionCouldRefresh()
//                    }
//                }
//        }
//    }
//
//    private fun sessionCouldRefresh() {
//        detectNewSession()
//        trackAppLaunch()
//        storage.recordFirstSeenTracked()
//    }
//
//    private fun detectNewSession() {
//        val didStartNewSession = AppSessionLogic.didStartNewSession(
//            lastAppClose,
//            appSessionTimeout
//        )
//
//        if (didStartNewSession) {
//            appSession = AppSession()
//            CoroutineScope(Dispatchers.IO).launch {
//                Superwall.shared.track(InternalSuperwallEvent.SessionStart())
//            }
//        } else {
//            appSession.endAt = null
//        }
//    }
//
//    private fun trackAppLaunch() {
//        if (didTrackAppLaunch) {
//            return
//        }
//        CoroutineScope(Dispatchers.IO).launch {
//            Superwall.shared.track(InternalSuperwallEvent.AppLaunch())
//        }
//        didTrackAppLaunch = true
//    }
//}
//
//object ApplicationObserver {
//    var willResignActive: () -> Unit = {}
//    var didBecomeActive: () -> Unit = {}
//    var willTerminate: () -> Unit = {}
//}
