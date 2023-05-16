package com.superwall.sdk.models.paywall

import kotlinx.serialization.Serializable

// Assuming StoreProduct is defined something like this:
@Serializable
data class StoreProduct(val productIdentifier: String)

@Serializable
class PaywallProducts(
    val primary: StoreProduct? = null,
    val secondary: StoreProduct? = null,
    val tertiary: StoreProduct? = null
) {
    val ids: List<String> = listOfNotNull(primary?.productIdentifier, secondary?.productIdentifier, tertiary?.productIdentifier)
}
