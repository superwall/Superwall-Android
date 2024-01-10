package com.superwall.sdk.identity

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.misc.awaitFirstValidConfig
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class IdentityManager(
    private val deviceHelper: DeviceHelper,
    private val storage: Storage,
    private val configManager: ConfigManager
) {
    private var _appUserId: String? = storage.get(AppUserId)

    val appUserId: String?
        get() = runBlocking(queue) {
            _appUserId
        }

    private var _aliasId: String =
        storage.get(AliasId) ?: IdentityLogic.generateAlias()

    val aliasId: String
        get() = runBlocking(queue) {
            _aliasId
        }

    private var _seed: Int =
        storage.get(Seed) ?: IdentityLogic.generateSeed()

    val seed: Int
        get() = runBlocking(queue) {
            _seed
        }

    val userId: String
        get() = runBlocking(queue) {
            _appUserId ?: _aliasId
        }

    private var _userAttributes: Map<String, Any> =
        storage.get(UserAttributes) ?: emptyMap()

    val userAttributes: Map<String, Any>
        get() = runBlocking(queue) {
            _userAttributes
        }

    val isLoggedIn: Boolean get() = _appUserId != null

    private val identityFlow = MutableStateFlow(false)
    val hasIdentity: Flow<Boolean> get() = identityFlow.asStateFlow().filter { it }

    private val queue = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(queue)
    private val identityJobs = mutableListOf<Job>()

    init {
        val extraAttributes = mutableMapOf<String, Any>()

        val aliasId = storage.get(AliasId)
        if (aliasId == null) {
            storage.save(_aliasId, AliasId)
            extraAttributes["aliasId"] = _aliasId
        }

        val seed = storage.get(Seed)
        if (seed == null) {
            storage.save(_seed, Seed)
            extraAttributes["seed"] = _seed
        }

        if (extraAttributes.isNotEmpty()) {
            mergeUserAttributes(
                newUserAttributes = extraAttributes,
                shouldTrackMerge = false
            )
        }
    }

    fun configure() {
        CoroutineScope(queue).launch {
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
                if (_appUserId == sanitizedUserId || sanitizedUserId == "") {
                    if (sanitizedUserId == "") {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.identityManager,
                            message = "The provided userId was empty."
                        )
                    }
                    return@launch
                }

                identityFlow.emit(false)

                val oldUserId = _appUserId
                if (oldUserId != null && sanitizedUserId != oldUserId) {
                    Superwall.instance.reset(duringIdentify = true)
                }

                _appUserId = sanitizedUserId

                // If we haven't gotten config yet, we need
                // to leave this open to grab the appUserId for headers
                identityJobs += CoroutineScope(Dispatchers.IO).launch {
                    val config = configManager.configState.awaitFirstValidConfig()

                    if (config?.featureFlags?.enableUserIdSeed == true) {
                        sanitizedUserId.sha256MappedToRange()?.let { seed ->
                            _seed = seed
                            saveIds()
                        }
                    }
                }

                saveIds()

                if (options?.restorePaywallAssignments == true) {
                    identityJobs += CoroutineScope(Dispatchers.IO).launch {
                        configManager.getAssignments()
                        didSetIdentity()
                    }
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        configManager.getAssignments()
                    }
                    didSetIdentity()
                }
            }
        }
    }

    private fun didSetIdentity() {
        scope.launch {
            identityJobs.forEach { it.join() }
            identityFlow.emit(true)
        }
    }

    /**
     * Saves the `aliasId`, `seed` and `appUserId` to storage and user attributes.
      */
    private fun saveIds() {
        // This is not wrapped in a scope/mutex because is
        // called from the didSet of vars, who are already
        // being set within the queue.
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

        _mergeUserAttributes(
            newUserAttributes = newUserAttributes
        )
    }

    fun reset(duringIdentify: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            identityFlow.emit(false)
        }

        if (duringIdentify) {
            _reset()
        } else {
            scope.launch {
                _reset()
                didSetIdentity()
            }
        }
    }

    private fun _reset() {
        _appUserId = null
        _aliasId = IdentityLogic.generateAlias()
        _seed = IdentityLogic.generateSeed()
        _userAttributes = emptyMap()
        saveIds()
    }

    fun mergeUserAttributes(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true
    ) {
        scope.launch {
            _mergeUserAttributes(
                newUserAttributes = newUserAttributes,
                shouldTrackMerge = shouldTrackMerge
            )
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