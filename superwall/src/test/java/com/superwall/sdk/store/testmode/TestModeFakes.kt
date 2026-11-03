package com.superwall.sdk.store.testmode

import android.app.Activity
import com.superwall.sdk.storage.TestModeSettings
import com.superwall.sdk.store.testmode.ui.TestModeModalResult

/** Unit tests run without an activity, so the test-mode modal must never be shown. */
internal val noTestModeModal: suspend (
    activity: Activity,
    reason: String,
    hasPurchaseController: Boolean,
    availableEntitlements: List<String>,
    apiKey: String,
    dashboardBaseUrl: String,
    savedSettings: TestModeSettings?,
) -> TestModeModalResult = { _, _, _, _, _, _, _ ->
    error("The test-mode modal is not expected in unit tests")
}
