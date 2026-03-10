package com.superwall.sdk.identity

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.misc.engine.createEffectRunner
import com.superwall.sdk.misc.primitives.Engine
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.DidTrackFirstSeen
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.Executors

/**
 * Facade over the Engine-based identity system.
 *
 * External API is identical to the old IdentityManager — all callers
 * (Superwall.kt, DependencyContainer, PublicIdentity) remain unchanged.
 *
 * Internally, every method dispatches an [IdentityState.Updates] event to the
 * engine, and every property reads from `engine.state.value.identity`.
 */
class IdentityManager(
    private val deviceHelper: DeviceHelper,
    private val storage: Storage,
    private val configManager: ConfigManager,
    private val ioScope: IOScope,
    private val neverCalledStaticConfig: () -> Boolean,
    private val stringToSha: (String) -> String = { it },
    private val notifyUserChange: (change: Map<String, Any>) -> Unit,
    private val completeReset: () -> Unit = {
        Superwall.instance.reset(duringIdentify = true)
    },
    private val track: suspend (TrackableSuperwallEvent) -> Unit = {
        Superwall.instance.track(it)
    },
    private val webPaywallRedeemer: (() -> WebPaywallRedeemer)? = null,
    private val testModeManager: TestModeManager? = null,
    private val delegate: (() -> SuperwallDelegateAdapter)? = null,
) {
    // Single-threaded dispatcher for the engine loop
    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val engineScope = CoroutineScope(engineDispatcher)

    // Root reducer: routes SdkEvent subtypes to slice reducers

    // The engine — single event loop, one source of truth
    internal val engine: Engine

    init {
        val initial =
            SdkState(
                identity = createInitialIdentityState(storage, deviceHelper.appInstalledAtString),
            )

        val runEffect =
            createEffectRunner(
                storage = storage,
                track = { track(it as TrackableSuperwallEvent) },
                configProvider = { configManager.config },
                webPaywallRedeemer = webPaywallRedeemer,
                testModeManager = testModeManager,
                deviceHelper = deviceHelper,
                delegate = delegate,
                completeReset = completeReset,
                fetchAssignments = { configManager.getAssignments() },
                notifyUserChange = notifyUserChange,
            )

        engine =
            Engine(
                initial = initial,
                runEffect = runEffect,
                scope = engineScope,
            )
    }

    // -----------------------------------------------------------------------
    // State reads — no runBlocking, no locks, just read the StateFlow
    // -----------------------------------------------------------------------

    private val identity get() = engine.state.value.identity

    val appUserId: String? get() = identity.appUserId

    val aliasId: String get() = identity.aliasId

    val seed: Int get() = identity.seed

    val userId: String get() = identity.userId

    val userAttributes: Map<String, Any> get() = identity.enrichedAttributes

    val isLoggedIn: Boolean get() = identity.isLoggedIn

    val externalAccountId: String
        get() =
            if (configManager.options.passIdentifiersToPlayStore) {
                userId
            } else {
                stringToSha(userId)
            }

    val hasIdentity: Flow<Boolean>
        get() = engine.state.map { it.identity.isReady }.filter { it }

    // -----------------------------------------------------------------------
    // Actions — dispatch events instead of mutating state directly
    // -----------------------------------------------------------------------

    private fun dispatchIdentity(update: IdentityState.Updates) {
        engine.dispatch(SdkState.Updates.UpdateIdentity(update))
    }

    fun configure() {
        dispatchIdentity(
            IdentityState.Updates.Configure(
                neverCalledStaticConfig = neverCalledStaticConfig(),
                isFirstAppOpen = !(storage.read(DidTrackFirstSeen) ?: false),
            ),
        )
    }

    fun identify(
        userId: String,
        options: IdentityOptions? = null,
    ) {
        dispatchIdentity(IdentityState.Updates.Identify(userId, options))
    }

    fun reset(duringIdentify: Boolean) {
        if (duringIdentify) {
            // No-op: when called from Superwall.reset(duringIdentify=true) during
            // an identify flow, the Identify reducer already handles identity reset
            // inline. The completeReset callback only resets OTHER managers.
        } else {
            dispatchIdentity(IdentityState.Updates.Reset)
        }
    }

    fun mergeUserAttributes(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true,
    ) {
        dispatchIdentity(
            IdentityState.Updates.AttributesMerged(
                attrs = newUserAttributes,
                shouldTrackMerge = shouldTrackMerge,
            ),
        )
    }

    internal fun mergeAndNotify(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true,
    ) {
        dispatchIdentity(
            IdentityState.Updates.AttributesMerged(
                attrs = newUserAttributes,
                shouldTrackMerge = shouldTrackMerge,
                shouldNotify = true,
            ),
        )
    }
}
