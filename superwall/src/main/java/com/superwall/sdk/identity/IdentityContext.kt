package com.superwall.sdk.identity

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.config.ConfigContext
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.misc.primitives.SdkContext
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer

/**
 * All dependencies available to identity [IdentityState.Actions].
 *
 * Actions see only [IdentityState] via [actor]. For cross-state
 * coordination (e.g. fetching assignments), use [configState] +
 * [configCtx] with [StateActor.dispatchAwait].
 */
internal interface IdentityContext : SdkContext<IdentityState, IdentityContext> {
    val configProvider: () -> Config?
    val configManager: ConfigManager
    val configState: StateActor<SdkConfigState>

    /** ConfigManager implements ConfigContext — use it directly for cross-state dispatch. */
    val configCtx: ConfigContext get() = configManager

    val webPaywallRedeemer: (() -> WebPaywallRedeemer)?
    val testModeManager: TestModeManager?
    val deviceHelper: DeviceHelper
    val completeReset: () -> Unit
    val track: suspend (Trackable) -> Unit
    val notifyUserChange: ((Map<String, Any>) -> Unit)?
}
