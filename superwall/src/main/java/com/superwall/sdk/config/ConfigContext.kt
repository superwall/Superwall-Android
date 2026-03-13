package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.SdkContext
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
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
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * All dependencies available to config [SdkConfigState.Actions].
 *
 * Actions see only [SdkConfigState] via [actor]. Lifting to the
 * root [SdkState] is automatic and invisible.
 */
interface ConfigContext :
    BaseContext<SdkConfigState, ConfigContext>,
    RequestFactory,
    RuleAttributesFactory,
    DeviceHelperFactory,
    StoreTransactionFactory,
    HasExternalPurchaseControllerFactory {
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
    val ioScope: IOScope
    val track: suspend (InternalSuperwallEvent) -> Unit
    val testModeManager: TestModeManager?
    val identityManager: (() -> IdentityManager)?
    val activityProvider: ActivityProvider?
    val activityTracker: CurrentActivityTracker?
    val setSubscriptionStatus: ((SubscriptionStatus) -> Unit)?
    val webPaywallRedeemer: () -> WebPaywallRedeemer
    val awaitUntilNetwork: suspend () -> Unit
    val sdkContext: SdkContext
    val neverCalledStaticConfig: () -> Boolean

    /** Await until config is available from the actor state. */
    suspend fun awaitConfig(): Config? =
        try {
            state.filterIsInstance<SdkConfigState.Phase.Retrieved>().first().config
        } catch (_: Throwable) {
            null
        }
}
