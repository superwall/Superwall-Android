package com.superwall.sdk.paywall.presentation

@kotlinx.serialization.Serializable
sealed class PaywallCloseReason {
    /// The paywall was closed by system logic, either after a purchase, because
    /// a deeplink was presented, close button pressed, etc.
    object SystemLogic: PaywallCloseReason()

    /// The paywall was automatically closed becacuse another paywall will show.
    ///
    /// This prevents ``Superwall/register(event:params:handler:feature:)`` `feature`
    /// block from executing on dismiss of the paywall, because another paywall is set to show
    object ForNextPaywall: PaywallCloseReason()
}