package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaywallPresentationInfo(

    @SerialName("style")
    // The presentation style of the paywall
    val style: PaywallPresentationStyle,

    @SerialName("condition")
    // The condition for when a paywall should present.
    val condition: PresentationCondition,

    @SerialName("delay")
    // The delay in milliseconds before switching from the loading view to
    // the paywall view.
    val delay: Long
)
