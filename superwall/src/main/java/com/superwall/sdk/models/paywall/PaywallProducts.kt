package com.superwall.sdk.models.paywall

import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.serialization.Serializable

// Assuming StoreProduct is defined something like this:
//@Serializable
//data class StoreProduct(val productIdentifier: String)

class PaywallProducts(
    val primary: StoreProduct? = null,
    val secondary: StoreProduct? = null,
    val tertiary: StoreProduct? = null
) {
    val ids: List<String> = listOfNotNull(
        primary?.fullIdentifier,
        secondary?.fullIdentifier,
        tertiary?.fullIdentifier
    )
}
