package com.superwall.sdk.models.entitlements

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RedeemRequest(
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("appUserId")
    val userId: String,
    @SerialName("codes")
    val codes: List<Reedemable>,
)

@Serializable
data class Reedemable(
    @SerialName("code")
    val code: String,
)

@Serializable
data class RedemptionEmail(
    val email: String,
)
