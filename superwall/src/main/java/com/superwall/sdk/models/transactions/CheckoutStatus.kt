package com.superwall.sdk.models.transactions

import com.superwall.sdk.models.product.StripeProductType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal

typealias CheckoutId = String

@Serializable
data class CheckoutStatusRequest(
    @SerialName("checkoutId")
    val id: CheckoutId,
)

@Serializable
data class AbandonedCheckout(
    @SerialName("paywall_id")
    val paywallId: String,
    @SerialName("experiment_variant_id")
    val variantId: String,
    @SerialName("presented_by_event_name")
    val presentedByEventName: String,
    @SerialName("stripe_product")
    val stripeProduct: StripeProductType,
)

@Serializable(with = CheckoutStatusSerializer::class)
sealed class CheckoutStatus {
    @Serializable
    object Pending : CheckoutStatus()

    @Serializable
    data class Completed(
        @SerialName("redemption_codes")
        val redemptionCodes: List<String>,
        @SerialName("product")
        val product: StripeProductType,
    ) : CheckoutStatus()

    @Serializable
    data class Abandoned(
        val abandonedCheckout: AbandonedCheckout,
    ) : CheckoutStatus()
}

object CheckoutStatusSerializer : KSerializer<CheckoutStatus> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CheckoutStatus")

    override fun serialize(
        encoder: Encoder,
        value: CheckoutStatus,
    ) {
        val jsonEncoder =
            encoder as? kotlinx.serialization.json.JsonEncoder
                ?: throw SerializationException("This class can only be serialized by Json")

        val jsonObj =
            when (value) {
                is CheckoutStatus.Pending ->
                    buildJsonObject {
                        put("type", "pending")
                    }

                is CheckoutStatus.Completed ->
                    buildJsonObject {
                        put("type", "completed")
                        put(
                            "redemption_codes",
                            kotlinx.serialization.json.JsonArray(
                                value.redemptionCodes.map { kotlinx.serialization.json.JsonPrimitive(it) },
                            ),
                        )
                        // Add product fields directly to the object
                        put("product_id", value.product.id)
                        put("raw_price", value.product.price.toString())
                        put("price", value.product.localizedPrice)
                        put("currency_code", value.product.currencyCode)
                        put("currency_symbol", value.product.currencySymbol)
                        // Add other product fields as needed
                    }

                is CheckoutStatus.Abandoned ->
                    buildJsonObject {
                        put("type", "abandoned")
                        put("paywall_id", value.abandonedCheckout.paywallId)
                        put("experiment_variant_id", value.abandonedCheckout.variantId)
                        put("presented_by_event_name", value.abandonedCheckout.presentedByEventName)
                        // Add product fields
                        put("product_id", value.abandonedCheckout.stripeProduct.id)
                        put(
                            "raw_price",
                            value.abandonedCheckout.stripeProduct.price
                                .toString(),
                        )
                        put("price", value.abandonedCheckout.stripeProduct.localizedPrice)
                        put("currency_code", value.abandonedCheckout.stripeProduct.currencyCode)
                        put("currency_symbol", value.abandonedCheckout.stripeProduct.currencySymbol)
                    }
            }
        jsonEncoder.encodeJsonElement(jsonObj)
    }

    override fun deserialize(decoder: Decoder): CheckoutStatus {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("This class can only be deserialized by Json")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

        val type =
            jsonObject["type"]?.jsonPrimitive?.content
                ?: throw SerializationException("Missing type field")

        return when (type) {
            "pending" -> CheckoutStatus.Pending
            "completed" -> {
                val redemptionCodes =
                    jsonObject["redemption_codes"]?.let { element ->
                        when (element) {
                            is kotlinx.serialization.json.JsonArray -> element.map { it.jsonPrimitive.content }
                            else -> throw SerializationException("redemption_codes must be an array")
                        }
                    } ?: emptyList()

                val product = parseStripeProduct(jsonObject)
                CheckoutStatus.Completed(redemptionCodes, product)
            }

            "abandoned" -> {
                val paywallId =
                    jsonObject["paywall_id"]?.jsonPrimitive?.content
                        ?: throw SerializationException("Missing paywall_id")
                val variantId =
                    jsonObject["experiment_variant_id"]?.jsonPrimitive?.content
                        ?: throw SerializationException("Missing experiment_variant_id")
                val presentedByEventName =
                    jsonObject["presented_by_event_name"]?.jsonPrimitive?.content
                        ?: throw SerializationException("Missing presented_by_event_name")

                val product = parseStripeProduct(jsonObject)
                val abandonedCheckout =
                    AbandonedCheckout(paywallId, variantId, presentedByEventName, product)
                CheckoutStatus.Abandoned(abandonedCheckout)
            }

            else -> throw SerializationException("Unknown checkout status type: $type")
        }
    }

    private fun parseStripeProduct(jsonObject: JsonObject): StripeProductType {
        val priceLocaleObj = jsonObject["price_locale"]?.jsonObject
        val priceLocale =
            if (priceLocaleObj != null) {
                StripeProductType.PriceLocale(
                    identifier = priceLocaleObj["identifier"]?.jsonPrimitive?.content ?: "",
                    languageCode = priceLocaleObj["language_code"]?.jsonPrimitive?.content ?: "",
                    currencyCode = priceLocaleObj["currency_code"]?.jsonPrimitive?.content ?: "",
                    currencySymbol = priceLocaleObj["currency_symbol"]?.jsonPrimitive?.content ?: "",
                )
            } else {
                StripeProductType.PriceLocale("", "", "", "")
            }

        val subscriptionPeriod =
            jsonObject["subscription_period"]?.jsonObject?.let { periodObj ->
                StripeProductType.StripeSubscriptionPeriod(
                    unit =
                        periodObj["unit"]?.jsonPrimitive?.content?.let {
                            StripeProductType.StripeSubscriptionPeriod.Unit.valueOf(it)
                        } ?: StripeProductType.StripeSubscriptionPeriod.Unit.month,
                    value = periodObj["value"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                )
            }

        val subscriptionIntroOffer =
            jsonObject["subscription_introductory_offer"]?.jsonObject?.let { offerObj ->
                val period =
                    offerObj["period"]?.jsonObject?.let { periodObj ->
                        StripeProductType.StripeSubscriptionPeriod(
                            unit =
                                periodObj["unit"]?.jsonPrimitive?.content?.let {
                                    StripeProductType.StripeSubscriptionPeriod.Unit.valueOf(it)
                                } ?: StripeProductType.StripeSubscriptionPeriod.Unit.month,
                            value = periodObj["value"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                        )
                    } ?: StripeProductType.StripeSubscriptionPeriod(
                        StripeProductType.StripeSubscriptionPeriod.Unit.month,
                        1,
                    )

                StripeProductType.SubscriptionIntroductoryOffer(
                    period = period,
                    localizedPrice = offerObj["price"]?.jsonPrimitive?.content ?: "",
                    price =
                        offerObj["raw_price"]?.jsonPrimitive?.content?.let { BigDecimal(it) }
                            ?: BigDecimal.ZERO,
                    periodCount =
                        offerObj["period_count"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?: 1,
                    paymentMethod =
                        offerObj["payment_method"]?.jsonPrimitive?.content?.let {
                            StripeProductType.SubscriptionIntroductoryOffer.PaymentMethod.valueOf(it)
                        } ?: StripeProductType.SubscriptionIntroductoryOffer.PaymentMethod.freeTrial,
                )
            }

        return StripeProductType(
            id = jsonObject["product_id"]?.jsonPrimitive?.content ?: "",
            price =
                jsonObject["raw_price"]?.jsonPrimitive?.content?.let { BigDecimal(it) }
                    ?: BigDecimal.ZERO,
            localizedPrice = jsonObject["price"]?.jsonPrimitive?.content ?: "",
            currencyCode = jsonObject["currency_code"]?.jsonPrimitive?.content ?: "",
            currencySymbol = jsonObject["currency_symbol"]?.jsonPrimitive?.content ?: "",
            priceLocale = priceLocale,
            stripeSubscriptionPeriod = subscriptionPeriod,
            subscriptionIntroOffer = subscriptionIntroOffer,
            entitlements = emptyList(),
        )
    }
}

@Serializable
data class CheckoutStatusResponse(
    @SerialName("status")
    val status: CheckoutStatus,
)
