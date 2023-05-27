package com.superwall.sdk.paywall.request

import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.PaywallProducts

// File.kt

data class PaywallRequest(
    var eventData: EventData?,
    val responseIdentifiers: ResponseIdentifiers,
    val overrides: Overrides,
    val isDebuggerLaunched: Boolean
) {
    data class Overrides(
        var products: PaywallProducts?,
        var isFreeTrial: Boolean?
    )
}
