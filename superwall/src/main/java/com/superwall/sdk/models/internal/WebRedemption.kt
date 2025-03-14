@file:OptIn(ExperimentalSerializationApi::class)

package com.superwall.sdk.models.internal

import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.paywall.PaywallIdentifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

typealias ExperimentId = String
typealias VariantId = String
typealias RedemptionCode = String

@Serializable
data class WebRedemptionResponse(
    @SerialName("codes")
    val codes: List<RedemptionResult>,
    @SerialName("entitlements")
    val entitlements: List<Entitlement>,
    @Transient
    val allCodes: List<Redeemable> = codes.map { Redeemable(it.code, false) },
)

@Serializable
@Polymorphic
@JsonClassDiscriminator("status")
sealed class RedemptionResult {
    abstract val code: RedemptionCode

    // Represents that a redemption was successful
    @Serializable
    @SerialName("SUCCESS")
    data class Success(
        @SerialName("code")
        override val code: RedemptionCode,
        @SerialName("redemptionInfo")
        val redemptionInfo: RedemptionInfo,
    ) : RedemptionResult()

    // Represents that a redemption failed
    @Serializable
    @SerialName("ERROR")
    data class Error(
        @SerialName("code")
        override val code: RedemptionCode,
        @SerialName("error")
        val error: ErrorInfo,
    ) : RedemptionResult()

    // Code expired
    @Serializable
    @SerialName("CODE_EXPIRED")
    data class Expired(
        @SerialName("code")
        override val code: RedemptionCode,
        @SerialName("expired")
        val expired: ExpiredInfo,
    ) : RedemptionResult()

    // Invalid code
    @Serializable
    @SerialName("INVALID_CODE")
    data class InvalidCode(
        @SerialName("code")
        override val code: RedemptionCode,
    ) : RedemptionResult()

    // Expired subscription
    @Serializable
    @SerialName("EXPIRED_SUBSCRIPTION")
    data class ExpiredSubscription(
        @SerialName("code")
        override val code: RedemptionCode,
        @SerialName("redemptionInfo")
        val redemptionInfo: RedemptionInfo,
    ) : RedemptionResult()

    @Serializable
    data class PaywallInfo(
        @SerialName("identifier")
        val identifier: PaywallIdentifier,
        @SerialName("placementName")
        val placementName: String,
        // For “placementParams”, we treat TS’s “Record<string, any>” as a Map<String, JsonElement>.
        @SerialName("placementParams")
        val placementParams: Map<String, JsonElement>,
        @SerialName("variantId")
        val variantId: VariantId,
        @SerialName("experimentId")
        val experimentId: ExperimentId,
    )
}

@Serializable
data class RedemptionInfo(
    // Ownership of the redemption
    @SerialName("ownership")
    val ownership: RedemptionOwnership,
    // Who originally bought the subscription we're redeeming.
    @SerialName("purchaserInfo")
    val purchaserInfo: PurchaserInfo,
    // Can be null if the redemption was not tied to a paywall,
    // like when an entitlement is granted directly from the dashboard.
    // TODO: Consider merging these into one "paywallInfo"
    @SerialName("paywallInfo")
    val paywallInfo: RedemptionResult.PaywallInfo? = null,
    // We'll always have a transaction info, b/c these fields are
    // present in the transaction info.
    // transactionInfo: TransactionInfo,
    // Entitlements that were granted as a result of the redemption.
    @SerialName("entitlements")
    val entitlements: List<Entitlement>,
)

@Serializable
data class PurchaserInfo(
    // Who actually bought the subscription, can be
    // used to alias the purchaser to the app user.
    @SerialName("appUserId")
    val appUserId: String,
    @SerialName("email")
    val email: String? = null,
    @SerialName("storeIdentifiers")
    val storeIdentifiers: StoreIdentifiers,
)

//
// Store Transaction Info
//
// Unique per store, can be used to identify real identifiers within each store.
// We'd have a "superwall" type, but it's not yet implemented.

@Serializable
@JsonClassDiscriminator("store")
sealed class StoreIdentifiers {
    @Serializable
    @SerialName("STRIPE")
    data class Stripe(
        @SerialName("stripeSubscriptionId")
        val stripeSubscriptionId: String,
    ) : StoreIdentifiers()

    @Serializable
    @SerialName("UNKNOWN")
    class Unknown : StoreIdentifiers()
}

@Serializable
data class ErrorInfo(
    @SerialName("message")
    val message: String,
)

@Serializable
data class ExpiredInfo(
    @SerialName("resent")
    val resent: Boolean,
    @SerialName("obfuscatedEmail")
    val obfuscatedEmail: String?,
)

@Serializable
sealed class RedemptionOwnership {
    @Serializable
    @SerialName("device")
    data class Device(
        @SerialName("deviceId")
        val deviceId: String,
    ) : RedemptionOwnership()

    @Serializable
    @SerialName("app_user")
    data class AppUser(
        @SerialName("appUserId")
        val appUserId: String,
    ) : RedemptionOwnership()
}

@Serializable
enum class RedemptionOwnershipType {
    @SerialName("device")
    Device,

    @SerialName("app_user")
    AppUser,
}
