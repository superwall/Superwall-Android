@file:OptIn(ExperimentalSerializationApi::class)

package com.superwall.sdk.models.internal

import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.paywall.PaywallIdentifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

typealias ExperimentId = String
typealias VariantId = String
typealias RedemptionCode = String

@Serializable
data class WebRedemptionResponse(
    @SerialName("codes")
    val codes: List<RedemptionResult>,
    @SerialName("customerInfo")
    val customerInfo: com.superwall.sdk.models.customer.CustomerInfo? = null,
    @kotlinx.serialization.Transient
    val allCodes: List<Redeemable> = codes.map { Redeemable(it.code, false) },
)

@Serializable
@JsonClassDiscriminator("status")
sealed class RedemptionResult {
    abstract val code: String

    val stripeSubscriptionId: List<String?>
        get() =
            when (this) {
                is RedemptionResult.Success ->
                    when (this.redemptionInfo.purchaserInfo?.storeIdentifiers) {
                        is StoreIdentifiers.Stripe ->
                            this.redemptionInfo.purchaserInfo
                                ?.storeIdentifiers
                                ?.subscriptionIds
                                ?: listOf()

                        else -> listOf()
                    }
                else -> listOf()
            }

    val paddleSubscriptionIds: List<String?>
        get() =
            when (this) {
                is RedemptionResult.Success ->
                    when (this.redemptionInfo.purchaserInfo?.storeIdentifiers) {
                        is StoreIdentifiers.Paddle -> {
                            this.redemptionInfo.purchaserInfo
                                ?.storeIdentifiers
                                ?.paddleSubscriptionIds ?: listOf()
                        }
                        else -> listOf()
                    }

                else -> listOf()
            }

    val subscriptionIds: List<String?>
        get() = stripeSubscriptionId + paddleSubscriptionIds

    // Represents that a redemption was successful
    @Serializable(with = DirectSuccessSerializer::class)
    @SerialName("SUCCESS")
    data class Success(
        @SerialName("code")
        override val code: String,
        @SerialName("redemption_info")
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
    class PaywallInfo
        @JvmOverloads
        constructor(
            @SerialName("identifier") val identifier: PaywallIdentifier,
            @SerialName("placementName") val placementName: String,
            @SerialName("placementParams") val placementParams: Map<String, JsonElement>,
            @SerialName("variantId") val variantId: VariantId,
            @SerialName("experimentId") val experimentId: ExperimentId,
            @SerialName("productIdentifier") val productIdentifier: String? = null,
        ) {
            /** Original checkout variables. Kept outside the constructor to preserve the Kotlin JVM ABI. */
            @SerialName("product")
            var product: PaywallProduct? = null
                private set

            constructor(
                identifier: PaywallIdentifier,
                placementName: String,
                placementParams: Map<String, JsonElement>,
                variantId: VariantId,
                experimentId: ExperimentId,
                productIdentifier: String? = null,
                product: PaywallProduct?,
            ) : this(identifier, placementName, placementParams, variantId, experimentId, productIdentifier) {
                this.product = product
            }

            // Retain the original copy/copy$default and component signatures for precompiled Kotlin callers.
            fun copy(
                identifier: PaywallIdentifier = this.identifier,
                placementName: String = this.placementName,
                placementParams: Map<String, JsonElement> = this.placementParams,
                variantId: VariantId = this.variantId,
                experimentId: ExperimentId = this.experimentId,
                productIdentifier: String? = this.productIdentifier,
            ): PaywallInfo = PaywallInfo(identifier, placementName, placementParams, variantId, experimentId, productIdentifier, product)

            fun copy(
                identifier: PaywallIdentifier = this.identifier,
                placementName: String = this.placementName,
                placementParams: Map<String, JsonElement> = this.placementParams,
                variantId: VariantId = this.variantId,
                experimentId: ExperimentId = this.experimentId,
                productIdentifier: String? = this.productIdentifier,
                product: PaywallProduct?,
            ): PaywallInfo = PaywallInfo(identifier, placementName, placementParams, variantId, experimentId, productIdentifier, product)

            operator fun component1(): PaywallIdentifier = identifier

            operator fun component2(): String = placementName

            operator fun component3(): Map<String, JsonElement> = placementParams

            operator fun component4(): VariantId = variantId

            operator fun component5(): ExperimentId = experimentId

            operator fun component6(): String? = productIdentifier

            operator fun component7(): PaywallProduct? = product

            override fun equals(other: Any?): Boolean =
                other is PaywallInfo &&
                    identifier == other.identifier && placementName == other.placementName &&
                    placementParams == other.placementParams && variantId == other.variantId &&
                    experimentId == other.experimentId && productIdentifier == other.productIdentifier && product == other.product

            override fun hashCode(): Int =
                listOf(identifier, placementName, placementParams, variantId, experimentId, productIdentifier, product).hashCode()

            override fun toString(): String =
                "PaywallInfo(identifier=$identifier, placementName=$placementName, placementParams=$placementParams, " +
                    "variantId=$variantId, experimentId=$experimentId, productIdentifier=$productIdentifier, product=$product)"

            @Serializable
            data class PaywallProduct(
                @SerialName("identifier")
                val identifier: String,
                @SerialName("languageCode")
                val languageCode: String = "",
                @SerialName("locale")
                val locale: String = "",
                @SerialName("currencyCode")
                val currencyCode: String = "",
                @SerialName("currencySymbol")
                val currencySymbol: String = "",
                @SerialName("period")
                val period: String = "",
                @SerialName("periodly")
                val periodly: String = "",
                @SerialName("localizedPeriod")
                val localizedPeriod: String = "",
                @SerialName("periodAlt")
                val periodAlt: String = "",
                @SerialName("periodDays")
                val periodDays: Int = 0,
                @SerialName("periodWeeks")
                val periodWeeks: Int = 0,
                @SerialName("periodMonths")
                val periodMonths: Int = 0,
                @SerialName("periodYears")
                val periodYears: Int = 0,
                @SerialName("rawPrice")
                val rawPrice: Double = 0.0,
                @SerialName("price")
                val price: String = "",
                @SerialName("dailyPrice")
                val dailyPrice: String = "",
                @SerialName("weeklyPrice")
                val weeklyPrice: String = "",
                @SerialName("monthlyPrice")
                val monthlyPrice: String = "",
                @SerialName("yearlyPrice")
                val yearlyPrice: String = "",
                @SerialName("rawTrialPeriodPrice")
                val rawTrialPeriodPrice: Double = 0.0,
                @SerialName("trialPeriodPrice")
                val trialPeriodPrice: String = "",
                @SerialName("trialPeriodDailyPrice")
                val trialPeriodDailyPrice: String = "",
                @SerialName("trialPeriodWeeklyPrice")
                val trialPeriodWeeklyPrice: String = "",
                @SerialName("trialPeriodMonthlyPrice")
                val trialPeriodMonthlyPrice: String = "",
                @SerialName("trialPeriodYearlyPrice")
                val trialPeriodYearlyPrice: String = "",
                @SerialName("trialPeriodDays")
                val trialPeriodDays: Int = 0,
                @SerialName("trialPeriodWeeks")
                val trialPeriodWeeks: Int = 0,
                @SerialName("trialPeriodMonths")
                val trialPeriodMonths: Int = 0,
                @SerialName("trialPeriodYears")
                val trialPeriodYears: Int = 0,
                @SerialName("trialPeriodText")
                val trialPeriodText: String = "",
                @SerialName("trialPeriodEndDate")
                val trialPeriodEndDate: String = "",
            )
        }
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
        @SerialName("stripeCustomerId")
        val stripeCustomerId: String,
        @SerialName("stripeSubscriptionIds")
        val subscriptionIds: List<String>,
    ) : StoreIdentifiers()

    @Serializable
    @SerialName("PADDLE")
    data class Paddle(
        @SerialName("paddleCustomerId")
        val paddleCustomerId: String,
        @SerialName("paddleSubscriptionIds")
        val paddleSubscriptionIds: List<String>,
    ) : StoreIdentifiers()

    @Serializable
    @SerialName("UNKNOWN")
    data class Unknown(
        val properties: Map<String, JsonElement?>,
    ) : StoreIdentifiers()
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
    val obfuscatedEmail: String? = null,
)

@Serializable
@JsonClassDiscriminator("type")
sealed class RedemptionOwnership {
    @Serializable
    @SerialName("DEVICE")
    data class Device(
        @SerialName("deviceId")
        val deviceId: String,
    ) : RedemptionOwnership()

    @Serializable
    @SerialName("APP_USER")
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

// Custom serializer due to issue with nested polymorphic serialization
object DirectSuccessSerializer : KSerializer<RedemptionResult.Success> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("SUCCESS") {
            element<String>("code")
            element<JsonElement>("redemptionInfo")
        }

    override fun serialize(
        encoder: Encoder,
        value: RedemptionResult.Success,
    ) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeStringElement(descriptor, 0, value.code)

        val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        val redemptionInfoJson =
            json.encodeToJsonElement(RedemptionInfo.serializer(), value.redemptionInfo)

        compositeEncoder.encodeSerializableElement(
            descriptor,
            1,
            JsonElement.serializer(),
            redemptionInfoJson,
        )

        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): RedemptionResult.Success {
        // Cast to JsonDecoder to access the JSON directly
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Expected JSON decoder")
        val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        // Get the input JSON object directly
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

        // Extract fields directly from JSON
        val code =
            jsonObject["code"]?.jsonPrimitive?.content
                ?: throw SerializationException("Required field 'code' was not found")

        // Get the redemptionInfo as a JsonElement
        val redemptionInfoJson =
            (jsonObject["redemption_info"] ?: jsonObject["redemptionInfo"])
                ?: throw SerializationException("Required field 'redemptionInfo' was not found")

        // Then parse it with the RedemptionInfo serializer
        val redemptionInfo =
            json.decodeFromJsonElement(
                RedemptionInfo.serializer(),
                redemptionInfoJson,
            )

        return RedemptionResult.Success(code = code, redemptionInfo = redemptionInfo)
    }
}
