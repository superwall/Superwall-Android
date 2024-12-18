package com.superwall.sdk.models.entitlements

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class EntitlementStatus {
    @Serializable
    object Unknown : EntitlementStatus()

    @Serializable
    object Inactive : EntitlementStatus()

    @Serializable
    data class Active(
        @SerialName("entitlements")
        val entitlements: Set<Entitlement>,
    ) : EntitlementStatus()
}
