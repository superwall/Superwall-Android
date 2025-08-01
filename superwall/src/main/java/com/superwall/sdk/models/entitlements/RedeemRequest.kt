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
    @SerialName("receipts")
    val receipts: List<TransactionReceipt>,
    @SerialName("externalAccountId")
    val externalAccountId: String,
)

@Serializable
data class Redeemable(
    @SerialName("code")
    val code: String,
    @SerialName("firstRedemption")
    val firstRedemption: Boolean? = false,
)

@Serializable
data class TransactionReceipt(
    @SerialName("jwsRepresentation")
    val purchaseToken: String,
) {
    @SerialName("type")
    val type: String = "Android"
}
