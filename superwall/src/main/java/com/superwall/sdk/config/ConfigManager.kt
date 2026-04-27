package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.dependencies.RequestFactory
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.awaitFirstValidConfig
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.awaitUntilNetworkExists
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * Facade over the config state of the shared SDK actor.
 *
 * Implements [ConfigContext] directly — actions receive `this` as their
 * context, eliminating the intermediate object. Public API is unchanged:
 * state-mutating entry points dispatch [ConfigState.Actions] through the
 * actor. Read-only entry points (`preloadAllPaywalls`, `preloadPaywallsByNames`,
 * `getAssignments`) await a valid config on the caller's scope and then
 * dispatch an action — so they never suspend on state transitions while
 * holding the queue.
 */
open class ConfigManager(
    override val context: Context,
    override val storeManager: StoreManager,
    override val entitlements: Entitlements,
    override val storage: Storage,
    override val network: SuperwallAPI,
    override val deviceHelper: DeviceHelper,
    override var options: SuperwallOptions,
    override val paywallManager: PaywallManager,
    override val webPaywallRedeemer: () -> WebPaywallRedeemer,
    override val factory: Factory,
    override val assignments: Assignments,
    override val paywallPreload: PaywallPreload,
    private val ioScope: IOScope,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    override val testMode: TestMode? = null,
    override val identityManager: (() -> IdentityManager)? = null,
    override val setSubscriptionStatus: ((SubscriptionStatus) -> Unit)? = null,
    override val awaitUtilNetwork: suspend () -> Unit = {
        context.awaitUntilNetworkExists()
    },
    override val activateTestMode: suspend (Config, Boolean) -> Unit = { _, _ -> },
    override val actor: StateActor<ConfigContext, ConfigState>,
) : ConfigContext {
    interface Factory :
        RequestFactory,
        DeviceInfoFactory,
        RuleAttributesFactory,
        DeviceHelperFactory,
        StoreTransactionFactory,
        HasExternalPurchaseControllerFactory

    override val scope: CoroutineScope get() = ioScope

    /** Exposed to existing call sites — back-compat with the old `MutableStateFlow`. */
    internal val configState: StateFlow<ConfigState> get() = actor.state

    val config: Config?
        get() {
            val current = actor.state.value
            if (current is ConfigState.Failed) {
                effect(ConfigState.Actions.FetchConfig)
            }
            return current.getConfig()
        }

    val hasConfig: Flow<Config> =
        actor.state
            .mapNotNull { it.getConfig() }
            .take(1)

    private var _triggersByEventName = mutableMapOf<String, Trigger>()
    var triggersByEventName: Map<String, Trigger>
        get() = _triggersByEventName
        set(value) {
            _triggersByEventName = value.toMutableMap()
        }

    override fun setTriggers(triggers: Map<String, Trigger>) {
        triggersByEventName = triggers
    }

    override val autoRetryCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun retryFetchConfig() {
        effect(ConfigState.Actions.FetchConfig)
    }

    val unconfirmedAssignments: Map<ExperimentID, Experiment.Variant>
        get() = assignments.unconfirmedAssignments

    suspend fun fetchConfiguration() {
        val current = actor.state.value
        if (current is ConfigState.Retrieving || current is ConfigState.Retrying) return
        immediate(ConfigState.Actions.FetchConfig)
    }

    /**
     * Synchronous on the caller's thread for the mutating parts — matches
     * pre-actor behavior where a caller could read `unconfirmedAssignments`
     * right after `reset()` and see the new picks. Only the follow-up
     * preload goes through the actor queue.
     */
    fun reset() {
        val config = actor.state.value.getConfig() ?: return
        assignments.reset()
        assignments.choosePaywallVariants(config.triggers)
        effect(ConfigState.Actions.PreloadIfEnabled)
    }

    /**
     * Re-evaluates test mode with the current identity and config.
     * If test mode was active but the current user no longer qualifies, clears test mode
     * and resets subscription status. If a new user qualifies, activates test mode and
     * shows the modal.
     *
     * Synchronous on the caller's thread for the mutating parts — matches
     * pre-actor behavior. Only the test-mode modal launch is off-thread.
     */
    fun reevaluateTestMode(
        config: Config? = null,
        appUserId: String? = null,
        aliasId: String? = null,
    ) {
        // Resolve config inside the body, not as a default parameter value —
        // evaluating actor state inside a default param runs on every call
        // even when the method is mocked/stubbed, which trips MockK.
        val resolvedConfig = config ?: actor.state.value.getConfig() ?: return
        val manager = testMode ?: return
        val wasTestMode = manager.isTestMode
        manager.evaluateTestMode(
            config = resolvedConfig,
            bundleId = deviceHelper.bundleId,
            appUserId = appUserId ?: identityManager?.invoke()?.appUserId,
            aliasId = aliasId ?: identityManager?.invoke()?.aliasId,
            testModeBehavior = options.testModeBehavior,
        )
        val isNowTestMode = manager.isTestMode
        if (wasTestMode && !isNowTestMode) {
            manager.clearTestModeState()
            setSubscriptionStatus?.invoke(SubscriptionStatus.Inactive)
        } else if (!wasTestMode && isNowTestMode) {
            ioScope.launch { activateTestMode(resolvedConfig, true) }
        }
    }

    suspend fun getAssignments() {
        actor.state.awaitFirstValidConfig()
        immediate(ConfigState.Actions.GetAssignments)
    }

    suspend fun preloadAllPaywalls() {
        actor.state.awaitFirstValidConfig()
        immediate(ConfigState.Actions.PreloadAll)
    }

    suspend fun preloadPaywallsByNames(eventNames: Set<String>) {
        actor.state.awaitFirstValidConfig()
        immediate(ConfigState.Actions.PreloadByNames(eventNames))
    }

    internal suspend fun refreshConfiguration(force: Boolean = false) {
        // Means config is currently being fetched, dont schedule refresh
        if (actor.state.value.getConfig() == null) return
        immediate(ConfigState.Actions.RefreshConfig(force = force))
    }

    // ---- Test-only helpers -------------------------------------------------

    /** Force the state to [ConfigState.Retrieved] with [config]. Tests only. */
    internal fun applyRetrievedConfigForTesting(config: Config) {
        actor.update(ConfigState.Updates.SetRetrieved(config))
    }

    /** Force the actor to any state without going through a fetch. Tests only. */
    internal fun setConfigStateForTesting(state: ConfigState) {
        actor.update(ConfigState.Updates.Set(state))
    }
}
