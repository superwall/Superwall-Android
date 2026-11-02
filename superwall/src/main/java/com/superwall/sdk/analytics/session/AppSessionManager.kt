package com.superwall.sdk.analytics.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.getConfig
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.dependencies.UserAttributesEventFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.Date

interface AppManagerDelegate {
    suspend fun didUpdateAppSession(appSession: AppSession)
}

//
class AppSessionManager internal constructor(
    private val configManager: ConfigManager,
    private val storage: LocalStorage,
    private val delegate: Factory,
    private val backgroundScope: IOScope,
    private val track: suspend (Trackable) -> Unit,
    private val republishAdConsentIfChanged: suspend () -> Unit,
) : DefaultLifecycleObserver {
    @Deprecated("Internal SDK type: the SDK builds it in Superwall.configure.")
    constructor(
        configManager: ConfigManager,
        storage: LocalStorage,
        delegate: Factory,
        backgroundScope: IOScope,
    ) : this(
        configManager = configManager,
        storage = storage,
        delegate = delegate,
        backgroundScope = backgroundScope,
        track = { SuperwallGraph.track(it) },
        republishAdConsentIfChanged = {
            SuperwallGraph.container?.let { it.adConsentPublisher.republishIfChanged() }
        },
    )

    interface Factory :
        AppManagerDelegate,
        DeviceHelperFactory,
        UserAttributesEventFactory

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
        backgroundScope.launch {
            track(InternalSuperwallEvent.AppOpen())
        }
        // A new session sends fresh device attributes, which already carry the current consent.
        val willSendDeviceAttributes =
            didTrackAppLaunch && AppSessionLogic.didStartNewSession(lastAppClose, appSessionTimeout)
        sessionCouldRefresh()
        if (!willSendDeviceAttributes) {
            // Catches a consent change made while the app was in the background, such as one
            // stored by a consent banner in another process.
            backgroundScope.launch {
                republishAdConsentIfChanged()
            }
        }
    }

    // Called when the app goes to the background
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        backgroundScope.launch {
            track(InternalSuperwallEvent.AppClose())
        }
        lastAppClose = Date()
        appSession.endAt = Date()
    }

    private fun willTerminate() {
        appSession.endAt = Date()
    }

    fun listenForAppSessionTimeout() {
        backgroundScope.launch {
            configManager.configState
                .mapNotNull { it.getConfig() }
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
        withErrorTracking {
            detectNewSession()
            trackAppLaunch()
            backgroundScope.launch {
                storage.recordFirstSeenTracked()
            }
        }
    }

    private fun detectNewSession() {
        withErrorTracking {
            val didStartNewSession =
                AppSessionLogic.didStartNewSession(
                    lastAppClose,
                    appSessionTimeout,
                )

            if (didStartNewSession) {
                appSession = AppSession()

                backgroundScope.launch {
                    val deviceAttributes = delegate.makeSessionDeviceAttributes()
                    val userAttributes = delegate.makeUserAttributesEvent()

                    track(InternalSuperwallEvent.SessionStart())

                    // Only track device attributes if we've already tracked app launch before.
                    // This is because we track device attributes after the config is first fetched.
                    // Otherwise we'd track it twice and it won't contain geo info here on cold app start.
                    if (didTrackAppLaunch) {
                        track(
                            InternalSuperwallEvent.DeviceAttributes(
                                deviceAttributes,
                            ),
                        )
                    }
                    track(userAttributes)
                }
                // If we are returning to the app, we can refresh the config here.
                backgroundScope.launch {
                    configManager.refreshConfiguration()
                }
            } else {
                appSession.endAt = null
            }
        }
    }

    //
    private fun trackAppLaunch() {
        if (didTrackAppLaunch) {
            return
        }
        backgroundScope.launch {
            track(InternalSuperwallEvent.AppLaunch())
        }
        didTrackAppLaunch = true
    }
}
