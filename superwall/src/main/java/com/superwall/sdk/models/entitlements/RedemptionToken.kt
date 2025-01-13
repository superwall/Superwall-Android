package com.superwall.sdk.models.entitlements

import kotlinx.serialization.Serializable

@Serializable
data class RedemptionToken(
    val token: String,
    val userId: String,
)

@Serializable
data class RedemptionEmail(
    val email: String,
)
