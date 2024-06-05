package com.superwall.sdk.models.paywall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PresentationCondition {
    @SerialName("ALWAYS")
    ALWAYS,

    @SerialName("CHECK_USER_SUBSCRIPTION")
    CHECK_USER_SUBSCRIPTION,
}
