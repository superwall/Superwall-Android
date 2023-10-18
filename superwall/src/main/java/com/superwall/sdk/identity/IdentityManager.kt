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
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.misc.sha256MappedToRange
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.AliasId
import com.superwall.sdk.storage.AppUserId
import com.superwall.sdk.storage.DidTrackFirstSeen
import com.superwall.sdk.storage.Seed
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.UserAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

class IdentityManager(
    private val deviceHelper: DeviceHelper,
    private val storage: Storage,
    private val configManager: ConfigManager
) {
    private var _appUserId: String? = storage.get(AppUserId)
        set(value) {
            field = value
            saveIds()
        }
    val appUserId: String? get() = _appUserId

    private var _aliasId: String =
        storage.get(AliasId) ?: IdentityLogic.generateAlias()
        set(value) {
            field = value
            saveIds()
        }
    suspend fun getAliasId(): String {
        return mutex.withLock {
            _aliasId
        }
    }

    private var _seed: Int =
        storage.get(Seed) ?: IdentityLogic.generateSeed()
        set(value) {
            field = value
            saveIds()
        }
    suspend fun getSeed(): Int {
        return mutex.withLock {
            _seed
        }
    }

    suspend fun getUserId(): String {
        return mutex.withLock {
            _appUserId ?: _aliasId
        }
    }

    private var _userAttributes: Map<String, Any> =
        storage.get(UserAttributes) ?: emptyMap()

    suspend fun getUserAttributes(): Map<String, Any> {
        return mutex.withLock {
            _userAttributes
        }
    }

    val isLoggedIn: Boolean get() = _appUserId != null

    private val identityFlow = MutableStateFlow(false)
    val hasIdentity: StateFlow<Boolean> get() = identityFlow.asStateFlow()

    private val mutex = Mutex()

    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(singleThreadDispatcher)

    init {
        val extraAttributes = mutableMapOf<String, Any>()

        val aliasId = storage.get(AliasId)
        if (aliasId == null) {
            _aliasId = IdentityLogic.generateAlias()
            storage.save(_aliasId, AliasId)
            extraAttributes["aliasId"] = _aliasId
        } else {
            this._aliasId = aliasId
        }

        val seed = storage.get(Seed)
        if (seed == null) {
            _seed = IdentityLogic.generateSeed()
            storage.save(_seed, Seed)
            extraAttributes["seed"] = _seed
        } else {
            this._seed = seed
        }

        if (extraAttributes.isNotEmpty()) {
            mergeUserAttributes(
                newUserAttributes = extraAttributes,
                shouldTrackMerge = false
            )
        }
    }

    public fun configure() {
        scope.launch {
            val neverCalledStaticConfig = storage.neverCalledStaticConfig
            val isFirstAppOpen =
                !(storage.get(DidTrackFirstSeen) ?: false)

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
                        Superwall.instance.reset(duringIdentify = true)
                    }

                    _appUserId = sanitizedUserId

                    val config = configManager.config

                    if (config?.value?.featureFlags?.enableUserIdSeed == true) {
                        userId.sha256MappedToRange()?.let { seed ->
                            _seed = seed
                        }
                    }

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
        scope.launch { identityFlow.emit(true) }
    }

    private fun saveIds() {
        scope.launch {
            mutex.withLock {
                _appUserId?.let {
                    storage.save(it, AppUserId)
                }
                storage.save(_aliasId, AliasId)
                storage.save(_seed, Seed)

                val newUserAttributes = mutableMapOf(
                    "aliasId" to _aliasId,
                    "seed" to _seed
                )
                _appUserId?.let { newUserAttributes["appUserId"] = it }

                mergeUserAttributes(newUserAttributes)
            }
        }
    }

    fun reset(duringIdentify: Boolean) {
        scope.launch {
            identityFlow.emit(false)
        }

        if (duringIdentify) {
            _reset()
        } else {
            scope.launch {
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
        _seed = IdentityLogic.generateSeed()
        _userAttributes = emptyMap()
    }

    fun mergeUserAttributes(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true
    ) {
        scope.launch {
            mutex.withLock {
                _mergeUserAttributes(
                    newUserAttributes = newUserAttributes,
                    shouldTrackMerge = shouldTrackMerge
                )
            }
        }
    }

    private fun _mergeUserAttributes(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true
    ) {
        val mergedAttributes = IdentityLogic.mergeAttributes(
            newAttributes = newUserAttributes,
            oldAttributes =_userAttributes,
            appInstalledAtString = deviceHelper.appInstalledAtString
        )

        if (shouldTrackMerge) {
            CoroutineScope(Dispatchers.IO).launch {
                val trackableEvent = InternalSuperwallEvent.Attributes(
                    deviceHelper.appInstalledAtString,
                    HashMap(mergedAttributes)
                )
                Superwall.instance.track(trackableEvent)
            }
        }

        storage.save(mergedAttributes, UserAttributes)
        _userAttributes = mergedAttributes
    }
}