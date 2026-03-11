package com.superwall.sdk.identity

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.DidTrackFirstSeen
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Facade over the identity state of the shared SDK actor.
 *
 * Implements [IdentityContext] directly — actions receive `this` as
 * their context, eliminating the intermediate object.
 */
class IdentityManager(
    override val deviceHelper: DeviceHelper,
    override val storage: Storage,
    override val configManager: ConfigManager,
    private val ioScope: IOScope,
    private val neverCalledStaticConfig: () -> Boolean,
    private val stringToSha: (String) -> String = { it },
    override val notifyUserChange: (change: Map<String, Any>) -> Unit,
    override val completeReset: () -> Unit = {
        Superwall.instance.reset(duringIdentify = true)
    },
    private val trackEvent: suspend (TrackableSuperwallEvent) -> Unit = {
        Superwall.instance.track(it)
    },
    override val webPaywallRedeemer: (() -> WebPaywallRedeemer)? = null,
    override val testModeManager: TestModeManager? = null,
    private val delegate: (() -> SuperwallDelegateAdapter)? = null,
    override val actor: StateActor<IdentityState>,
    private val configActor: StateActor<SdkConfigState>,
) : IdentityContext {
    // -- IdentityContext implementation --

    override val scope: CoroutineScope get() = ioScope
    override val configProvider: () -> Config? get() = { configManager.config }
    override val configState: StateActor<SdkConfigState> get() = configActor
    override val track: suspend (Trackable) -> Unit = { trackEvent(it as TrackableSuperwallEvent) }

    // -----------------------------------------------------------------------
    // State reads — no runBlocking, no locks, just read the StateFlow
    // -----------------------------------------------------------------------

    private val identity get() = actor.state.value

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
        get() = actor.state.map { it.isReady }.filter { it }

    // -----------------------------------------------------------------------
    // Actions — dispatch with self as context
    // -----------------------------------------------------------------------

    fun configure() {
        actor.dispatch(
            this,
            IdentityState.Actions.Configure(
                neverCalledStaticConfig = neverCalledStaticConfig(),
                isFirstAppOpen = !(storage.read(DidTrackFirstSeen) ?: false),
            ),
        )
    }

    fun identify(
        userId: String,
        options: IdentityOptions? = null,
    ) {
        actor.dispatch(this, IdentityState.Actions.Identify(userId, options))
    }

    fun reset(duringIdentify: Boolean) {
        if (!duringIdentify) {
            actor.dispatch(this, IdentityState.Actions.Reset)
        }
    }

    fun mergeUserAttributes(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true,
    ) {
        actor.dispatch(
            this,
            IdentityState.Actions.MergeAttributes(
                attrs = newUserAttributes,
                shouldTrackMerge = shouldTrackMerge,
                shouldNotify = false,
            ),
        )
    }

    internal fun mergeAndNotify(
        newUserAttributes: Map<String, Any?>,
        shouldTrackMerge: Boolean = true,
    ) {
        actor.dispatch(
            this,
            IdentityState.Actions.MergeAttributes(
                attrs = newUserAttributes,
                shouldTrackMerge = shouldTrackMerge,
                shouldNotify = true,
            ),
        )
    }
}
