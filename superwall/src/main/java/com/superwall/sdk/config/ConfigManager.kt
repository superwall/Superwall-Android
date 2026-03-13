package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.SdkContext
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.dependencies.RequestFactory
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.CurrentActivityTracker
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.StateStore
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.awaitUntilNetworkExists
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * Facade over the config state of the shared SDK actor.
 *
 * Implements [ConfigContext] directly — actions receive `this` as
 * their context, eliminating the intermediate object.
 */
open class ConfigManager(
    override val context: Context,
    override val storeManager: StoreManager,
    override val entitlements: Entitlements,
    override val storage: Storage,
    override val network: SuperwallAPI,
    override val fullNetwork: Network? = null,
    override val deviceHelper: DeviceHelper,
    override var options: SuperwallOptions,
    override val paywallManager: PaywallManager,
    override val webPaywallRedeemer: () -> WebPaywallRedeemer,
    factory: Factory,
    override val assignments: Assignments,
    override val paywallPreload: PaywallPreload,
    override val ioScope: IOScope,
    override val track: suspend (InternalSuperwallEvent) -> Unit,
    override val testModeManager: TestModeManager? = null,
    override val identityManager: (() -> IdentityManager)? = null,
    override val activityProvider: ActivityProvider? = null,
    override val activityTracker: CurrentActivityTracker? = null,
    override val setSubscriptionStatus: ((SubscriptionStatus) -> Unit)? = null,
    override val awaitUntilNetwork: suspend () -> Unit = {
        context.awaitUntilNetworkExists()
    },
    override val actor: StateActor<ConfigContext, SdkConfigState>,
    @Suppress("EXPOSED_PARAMETER_TYPE")
    override val sdkContext: SdkContext,
    override val neverCalledStaticConfig: () -> Boolean,
    actorScope: CoroutineScope = ioScope,
) : ConfigContext,
    StateStore<SdkConfigState> by actor,
    RequestFactory by factory,
    RuleAttributesFactory by factory,
    DeviceHelperFactory by factory,
    StoreTransactionFactory by factory,
    HasExternalPurchaseControllerFactory by factory {
    interface Factory :
        RequestFactory,
        RuleAttributesFactory,
        DeviceHelperFactory,
        StoreTransactionFactory,
        HasExternalPurchaseControllerFactory

    // -- ConfigContext: scope + options + configState --

    override val scope: CoroutineScope = actorScope

    // Need `override` on a mutable property — use backing field
    val configState: MutableStateFlow<ConfigState> = MutableStateFlow(ConfigState.None)

    init {
        // Keep configState in sync with actor state changes
        ioScope.launch {
            state.collect { slice ->
                val newState =
                    when (slice.phase) {
                        is SdkConfigState.Phase.None -> ConfigState.None
                        is SdkConfigState.Phase.Retrieving -> ConfigState.Retrieving
                        is SdkConfigState.Phase.Retrying -> ConfigState.Retrying
                        is SdkConfigState.Phase.Retrieved -> ConfigState.Retrieved(slice.phase.config)
                        is SdkConfigState.Phase.Failed -> ConfigState.Failed(slice.phase.error)
                    }
                configState.value = newState
            }
        }
    }

    // -----------------------------------------------------------------------
    // State reads
    // -----------------------------------------------------------------------

    /** Convenience variable to access config. */
    val config: Config?
        get() =
            configState.value
                .also {
                    if (it is ConfigState.Failed) {
                        effect(SdkConfigState.Actions.FetchConfig)
                    }
                }.getConfig()

    /** A flow that emits just once only when `config` is non-null. */
    val hasConfig: Flow<Config> =
        configState
            .mapNotNull { it.getConfig() }
            .take(1)

    /** A dictionary of triggers by their event name. */
    var triggersByEventName: Map<String, Trigger>
        get() = state.value.triggersByEventName
        set(value) {
            update(SdkConfigState.Updates.ConfigRetrieved(state.value.config ?: return))
        }

    /** A memory store of assignments that are yet to be confirmed. */
    val unconfirmedAssignments: Map<ExperimentID, Experiment.Variant>
        get() = assignments.unconfirmedAssignments

    // -----------------------------------------------------------------------
    // Actions — dispatch with self as context
    // -----------------------------------------------------------------------

    fun fetchConfiguration() {
        effect(SdkConfigState.Actions.FetchConfig)
    }

    fun reset() {
        effect(SdkConfigState.Actions.ResetAssignments)
    }

    /**
     * Re-evaluates test mode with the current identity and config.
     */
    fun reevaluateTestMode(
        config: Config? = this.config,
        appUserId: String? = null,
        aliasId: String? = null,
    ) {
        effect(
            SdkConfigState.Actions.ReevaluateTestMode(
                appUserId = appUserId,
                aliasId = aliasId,
            ),
        )
    }

    suspend fun getAssignments() {
        immediate(SdkConfigState.Actions.FetchAssignments)
    }

    // -----------------------------------------------------------------------
    // Preloading Paywalls
    // -----------------------------------------------------------------------

    fun preloadAllPaywalls() {
        effect(SdkConfigState.Actions.PreloadPaywalls)
    }

    fun preloadPaywallsByNames(eventNames: Set<String>) {
        effect(SdkConfigState.Actions.PreloadPaywallsByNames(eventNames))
    }

    internal fun refreshConfiguration(force: Boolean = false) {
        effect(SdkConfigState.Actions.RefreshConfig(force))
    }

    fun checkForWebEntitlements() {
        effect(SdkConfigState.Actions.CheckWebEntitlements)
    }
}
