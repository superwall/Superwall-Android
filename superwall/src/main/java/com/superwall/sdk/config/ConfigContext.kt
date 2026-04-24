package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.web.WebPaywallRedeemer

/**
 * All dependencies available to [ConfigState.Actions] running on the
 * config actor.
 *
 * The facade [ConfigManager] implements this interface directly — actions
 * receive `this` as their receiver and can read dependencies, dispatch
 * sub-actions, and apply pure [ConfigState.Updates] reducers to state.
 */
interface ConfigContext : BaseContext<ConfigState, ConfigContext> {
    val context: Context
    val storeManager: StoreManager
    val entitlements: Entitlements
    val network: SuperwallAPI
    val deviceHelper: DeviceHelper
    val options: SuperwallOptions
    val paywallManager: PaywallManager
    val webPaywallRedeemer: () -> WebPaywallRedeemer
    val factory: ConfigManager.Factory
    val assignments: Assignments
    val paywallPreload: PaywallPreload
    val track: suspend (InternalSuperwallEvent) -> Unit
    val testMode: TestMode?
    val identityManager: (() -> IdentityManager)?
    val setSubscriptionStatus: ((SubscriptionStatus) -> Unit)?
    val awaitUtilNetwork: suspend () -> Unit

    /**
     * Runs the test-mode UI flow: refreshes test products and (when
     * [justActivated] is true) presents the test-mode modal. Always invoked
     * via `scope.launch` from inside actions because the modal blocks on
     * user interaction and would otherwise pin the actor queue.
     *
     * Wired by `DependencyContainer` to a closure over `TestMode`,
     * the subscription network call, and the current activity — none of
     * which need to leak into the config slice directly.
     */
    val activateTestMode: suspend (config: Config, justActivated: Boolean) -> Unit

    /** Publish derived triggers-by-event-name map after processing a new config. */
    fun setTriggers(triggers: Map<String, Trigger>)

    /**
     * Re-dispatch [ConfigState.Actions.FetchConfig] from inside the action's
     * own failure path. Defined here (not inline) so the reference to the
     * `FetchConfig` object lives outside its own initializer — Kotlin forbids
     * self-references in the constructor of a nested object.
     */
    fun retryFetchConfig()
}
