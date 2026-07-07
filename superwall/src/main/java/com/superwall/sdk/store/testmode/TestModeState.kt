package com.superwall.sdk.store.testmode

import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.ui.EntitlementSelection
import kotlinx.coroutines.CompletableDeferred

sealed class TestModeState {
    data object Inactive : TestModeState()

    data class Active(
        val reason: TestModeReason,
        val session: TestModeSessionData = TestModeSessionData(),
    ) : TestModeState()
}

class TestModeSessionData {
    var products: List<SuperwallProduct> = emptyList()
    var testProductsByFullId: Map<String, StoreProduct> = emptyMap()

    // Completed once the test product catalog has been refreshed (successfully
    // or not), so product lookups can wait for it instead of racing activation
    // and falling through to Play billing.
    val productsLoaded: CompletableDeferred<Unit> = CompletableDeferred()
    var entitlementIds: MutableSet<String> = mutableSetOf()
    var entitlementSelections: List<EntitlementSelection> = emptyList()
    var freeTrialOverride: FreeTrialOverride = FreeTrialOverride.UseDefault
    var overriddenSubscriptionStatus: SubscriptionStatus? = null
}
