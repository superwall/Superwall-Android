package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.CurrentActivityTracker
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.awaitFirstValidConfig
import com.superwall.sdk.misc.primitives.SdkContext
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * All dependencies available to config [SdkConfigState.Actions].
 *
 * Actions see only [SdkConfigState] via [actor]. Lifting to the
 * root [SdkState] is automatic and invisible.
 */
internal interface ConfigContext : SdkContext<SdkConfigState, ConfigContext> {
    val context: Context
    val network: SuperwallAPI
    val fullNetwork: Network?
    val deviceHelper: DeviceHelper
    val storeManager: StoreManager
    val entitlements: Entitlements
    val options: SuperwallOptions
    val paywallManager: PaywallManager
    val paywallPreload: PaywallPreload
    val assignments: Assignments
    val factory: ConfigManager.Factory
    val ioScope: IOScope
    val track: suspend (InternalSuperwallEvent) -> Unit
    val testModeManager: TestModeManager?
    val identityManager: (() -> IdentityManager)?
    val activityProvider: ActivityProvider?
    val activityTracker: CurrentActivityTracker?
    val setSubscriptionStatus: ((SubscriptionStatus) -> Unit)?
    val webPaywallRedeemer: () -> WebPaywallRedeemer
    val awaitUntilNetwork: suspend () -> Unit

    /**
     * Compatibility: the legacy [MutableStateFlow<ConfigState>] that external
     * consumers still read from. Actions update this alongside the actor state.
     */
    val configState: MutableStateFlow<ConfigState>

    // ----- Convenience helpers -----

    /** Await until config is available, reading from the legacy configState flow. */
    suspend fun awaitConfig(): Config? =
        try {
            configState.awaitFirstValidConfig()
        } catch (_: Throwable) {
            null
        }

    /**
     * Shared logic for processing a fetched config: persist, extract entitlements,
     * choose assignments, evaluate test mode.
     */
    fun processConfig(config: Config) {
        storage.write(DisableVerboseEvents, config.featureFlags.disableVerboseEvents)
        if (config.featureFlags.enableConfigRefresh) {
            storage.write(LatestConfig, config)
        }
        assignments.choosePaywallVariants(config.triggers)

        // Extract entitlements from products and productsV3
        ConfigLogic.extractEntitlementsByProductId(config.products).let {
            entitlements.addEntitlementsByProductId(it)
        }
        config.productsV3?.let { productsV3 ->
            ConfigLogic.extractEntitlementsByProductIdFromCrossplatform(productsV3).let {
                entitlements.addEntitlementsByProductId(it)
            }
        }

        // Test mode evaluation
        val wasTestMode = testModeManager?.isTestMode == true
        testModeManager?.evaluateTestMode(
            config = config,
            bundleId = deviceHelper.bundleId,
            appUserId = identityManager?.invoke()?.appUserId,
            aliasId = identityManager?.invoke()?.aliasId,
            testModeBehavior = options.testModeBehavior,
        )
        val testModeJustActivated = !wasTestMode && testModeManager?.isTestMode == true

        if (testModeManager?.isTestMode == true) {
            if (testModeJustActivated) {
                val defaultStatus = testModeManager!!.buildSubscriptionStatus()
                testModeManager!!.setOverriddenSubscriptionStatus(defaultStatus)
                entitlements.setSubscriptionStatus(defaultStatus)
            }
            ioScope.launch {
                SdkConfigState.Actions
                    .FetchTestModeProducts(config, testModeJustActivated)
                    .execute
                    .invoke(this@ConfigContext)
            }
        } else {
            if (wasTestMode) {
                testModeManager?.clearTestModeState()
                setSubscriptionStatus?.invoke(SubscriptionStatus.Inactive)
            }
            ioScope.launch {
                storeManager.loadPurchasedProducts(entitlements.entitlementsByProductId)
            }
        }
    }
}
