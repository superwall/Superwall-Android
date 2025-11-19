package com.superwall.sdk.models.entitlements

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebEntitlements(
    @SerialName("entitlements")
    val entitlements: List<Entitlement>,
    @SerialName("customerInfo")
    val customerInfo: com.superwall.sdk.models.customer.CustomerInfo? = null,
)
