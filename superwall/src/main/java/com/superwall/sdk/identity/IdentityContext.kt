package com.superwall.sdk.identity

import com.superwall.sdk.SdkContext
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer

/**
 * All dependencies available to identity [IdentityState.Actions].
 *
 * Cross-slice dispatch goes through [sdkContext]. Config reads use [configManager].
 */
interface IdentityContext : BaseContext<IdentityState, IdentityContext> {
    val sdkContext: SdkContext
    val webPaywallRedeemer: (() -> WebPaywallRedeemer)?
    val testModeManager: TestModeManager?
    val deviceHelper: DeviceHelper
    val completeReset: () -> Unit
    val track: suspend (Trackable) -> Unit
    val notifyUserChange: ((Map<String, Any>) -> Unit)?
}
