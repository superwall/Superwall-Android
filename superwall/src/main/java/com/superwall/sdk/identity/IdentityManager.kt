package com.superwall.sdk.identity

//import com.superwall.sdk.config.ConfigManager
//import com.superwall.sdk.network.device.DeviceHelper
//import com.superwall.sdk.storage.Storage
//import kotlinx.coroutines.*
//import kotlinx.coroutines.sync.Mutex
//import kotlinx.coroutines.sync.withLock
//import kotlinx.coroutines.flow.*
import LogLevel
import LogScope
import Logger
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.keys.AliasId
import com.superwall.sdk.storage.keys.AppUserId
import com.superwall.sdk.storage.keys.UserAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IdentityManager(
    private val deviceHelper: DeviceHelper,
    private val storage: Storage,
    private val configManager: ConfigManager
) {
    private var _appUserId: String? = storage.cache.appUserId.get()?.appUserId ?: null
        set(value) {
            field = value
            saveIds()
        }
    val appUserId: String? get() = _appUserId

    private var _aliasId: String =
        storage.cache.aliasId.get()?.aliasId ?: IdentityLogic.generateAlias()
    val aliasId: String get() = _aliasId

    val userId: String get() = _appUserId ?: _aliasId

    private var _userAttributes: Map<String, Any> =
        storage.cache.userAttributes.get()?.attributes ?: emptyMap()

    suspend fun getUserAttributes(): Map<String, Any> {
        return mutex.withLock {
            _userAttributes
        }
    }



    val isLoggedIn: Boolean get() = _appUserId != null

    private val identityFlow = MutableStateFlow(false)
    val hasIdentity: StateFlow<Boolean> get() = identityFlow.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    public fun configure() {
        scope.launch {
            val neverCalledStaticConfig = storage.neverCalledStaticConfig
            val isFirstAppOpen =
                !(storage.cache.didTrackFirstSeen.get()?.didTrackFirstSeen ?: false)

            println("neverCalledStaticConfig: $neverCalledStaticConfig")
            println("isFirstAppOpen: $isFirstAppOpen")

            if (IdentityLogic.shouldGetAssignments(
                    isLoggedIn,
                    neverCalledStaticConfig,
                    isFirstAppOpen
                )
            ) {
                configManager.getAssignments()
            }
            didSetIdentity()
        }
    }

    fun identify(userId: String, options: IdentityOptions? = null) {
        scope.launch {
            IdentityLogic.sanitize(userId)?.let { sanitizedUserId ->
                mutex.withLock {
                    if (_appUserId == sanitizedUserId) return@withLock

                    identityFlow.emit(false)

                    val oldUserId = _appUserId
                    if (oldUserId != null && sanitizedUserId != oldUserId) {
                        // TODO: Call reset
//                        Superwall.shared.reset(duringIdentify = true)
                    }

                    _appUserId = sanitizedUserId

                    if (options?.restorePaywallAssignments == true) {
                        configManager.getAssignments()
                        didSetIdentity()
                    } else {
                        async {
                            configManager.getAssignments()
                            didSetIdentity()
                        }
                    }
                }
            } ?: Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.identityManager,
                message = "The provided userId was empty."
            )
        }
    }

    private fun didSetIdentity() {
        println("!! didSetIdentity")
        scope.launch { identityFlow.emit(true) }
    }

    private fun saveIds() {
        scope.launch {
            mutex.withLock {
                _appUserId?.let {
                    storage.cache.appUserId.set(AppUserId(it))
                }
                storage.cache.aliasId.set(AliasId(_aliasId))

                val newUserAttributes = mutableMapOf("aliasId" to _aliasId)
                _appUserId?.let { newUserAttributes["appUserId"] = it }

                mergeUserAttributes(newUserAttributes)
            }
        }
    }

    fun reset(duringIdentify: Boolean) {
        scope.launch {
            identityFlow.emit(false)

            if (duringIdentify) {
                _reset()
            } else {
                mutex.withLock {
                    _reset()
                    didSetIdentity()
                }
            }
        }
    }

    private fun _reset() {
        _appUserId = null
        _aliasId = IdentityLogic.generateAlias()
        _userAttributes = emptyMap()
    }

    fun mergeUserAttributes(newUserAttributes: Map<String, Any?>) {
        scope.launch {
            mutex.withLock {
                _mergeUserAttributes(newUserAttributes)
            }
        }
    }

    private fun _mergeUserAttributes(newUserAttributes: Map<String, Any?>) {
        val mergedAttributes = IdentityLogic.mergeAttributes(
            newUserAttributes,
            _userAttributes,
            deviceHelper.appInstalledAtString
        )

        scope.launch {
            // TODO: Track attributes
//            val trackableEvent = InternalSuperwallEvent.Attributes(
//                deviceHelper.appInstalledAtString,
//                mergedAttributes
//            )
//            Superwall.shared.track(trackableEvent)
        }
        storage.cache.userAttributes.set(UserAttributes(mergedAttributes))
        _userAttributes = mergedAttributes
    }
}