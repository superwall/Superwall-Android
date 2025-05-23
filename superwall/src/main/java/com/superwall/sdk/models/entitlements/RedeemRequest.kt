package com.superwall.sdk.models.entitlements

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RedeemRequest(
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("appUserId")
    val userId: String?,
    @SerialName("aliasId")
    val aliasId: String? = null,
    @SerialName("codes")
    val codes: List<Redeemable>,
)

@Serializable
data class Redeemable(
    @SerialName("code")
    val code: String,
    @SerialName("firstRedemption")
    val firstRedemption: Boolean? = false,
)
