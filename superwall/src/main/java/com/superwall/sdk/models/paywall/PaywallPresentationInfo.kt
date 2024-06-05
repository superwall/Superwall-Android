package com.superwall.sdk.models.paywall

import kotlinx.serialization.Serializable

@Serializable
data class PaywallPresentationInfo(
    // The presentation style of the paywall
    val style: PaywallPresentationStyle,

    // The condition for when a paywall should present.
    val condition: PresentationCondition,

    // The delay in milliseconds before switching from the loading view to
    // the paywall view.
    val delay: Long
)
