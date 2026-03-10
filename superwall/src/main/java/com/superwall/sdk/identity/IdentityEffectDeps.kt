package com.superwall.sdk.identity

import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer

internal interface IdentityEffectDeps {
    val configProvider: () -> Config?
    val webPaywallRedeemer: (() -> WebPaywallRedeemer)?
    val testModeManager: TestModeManager?
    val deviceHelper: DeviceHelper
    val delegate: (() -> SuperwallDelegateAdapter)?
    val completeReset: () -> Unit
    val fetchAssignments: (suspend () -> Unit)?
    val notifyUserChange: ((Map<String, Any>) -> Unit)?
}
