package com.superwall.sdk.paywall.presentation.internal.request

import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.paywall.PaywallProducts

// File.kt

class PaywallOverrides(
    val products: PaywallProducts? = null,
    val ignoreSubscriptionStatus: Boolean = false,
    val presentationStyle: PaywallPresentationStyle = PaywallPresentationStyle.NONE
) {
    // This constructor has been marked as deprecated, as it only sets products.
    @Deprecated("This constructor has been obsoleted.", ReplaceWith("PaywallOverrides(products)"))
    constructor(products: PaywallProducts?) : this(products, false, PaywallPresentationStyle.NONE)

    // This constructor has been marked as deprecated, as it only sets products and ignoreSubscriptionStatus.
    @Deprecated(
        "This constructor has been obsoleted.",
        ReplaceWith("PaywallOverrides(products, ignoreSubscriptionStatus)")
    )
    constructor(products: PaywallProducts?, ignoreSubscriptionStatus: Boolean) : this(
        products,
        ignoreSubscriptionStatus,
        PaywallPresentationStyle.NONE
    )
}
