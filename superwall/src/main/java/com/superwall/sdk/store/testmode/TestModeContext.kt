package com.superwall.sdk.store.testmode

import android.app.Activity
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.CurrentActivityTracker
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.TestModeSettings
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.testmode.models.SuperwallProductsResponse
import com.superwall.sdk.store.testmode.ui.TestModeModalResult

/**
 * Dependencies available to [TestModeState.Actions].
 *
 * Implemented directly by [TestMode] — actions receive the manager itself
 * as their context, mirroring the [com.superwall.sdk.identity.IdentityManager]
 * / [com.superwall.sdk.identity.IdentityContext] pattern.
 */
interface TestModeContext : BaseContext<TestModeState, TestModeContext> {
    val isTestEnvironment: Boolean
    val entitlements: Entitlements?
    val getSuperwallProducts: suspend () -> Either<SuperwallProductsResponse, NetworkError>
    val activityProvider: () -> ActivityProvider?
    val activityTracker: () -> CurrentActivityTracker?
    val hasExternalPurchaseController: () -> Boolean
    val apiKey: () -> String
    val dashboardBaseUrl: () -> String
    val showModal: suspend (
        activity: Activity,
        reason: String,
        hasPurchaseController: Boolean,
        availableEntitlements: List<String>,
        apiKey: String,
        dashboardBaseUrl: String,
        savedSettings: TestModeSettings?,
    ) -> TestModeModalResult
}
