package com.superwall.sdk.models.product

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.entitlements.Entitlement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = CrossplatformProductSerializer::class)
data class CrossplatformProduct(
    @SerialName("composite_id")
    val compositeId: String,
    @SerialName("store_product")
    val storeProduct: StoreProduct,
    @SerialName("entitlements")
    val entitlements: List<Entitlement>,
    // Note: This is used only by paywall as a reference to the object. Otherwise, it is empty.
    @SerialName("reference_name")
    val name: String,
) {
    @Serializable
    sealed class StoreProduct {
        @Serializable(with = PlayStoreSerializer::class)
        @SerialName("PLAY_STORE")
        data class PlayStore(
            @SerialName("product_identifier")
            val productIdentifier: String,
            @SerialName("base_plan_identifier")
            val basePlanIdentifier: String,
            @SerialName("offer")
            val offer: Offer,
        ) : StoreProduct() {
            val fullIdentifier: String
                get() =
                    when (offer) {
                        is Offer.Automatic -> "$productIdentifier:$basePlanIdentifier:sw-auto"
                        is Offer.Specified -> "$productIdentifier:$basePlanIdentifier:${offer.offerIdentifier}"
                    }
        }

        @Serializable(with = AppStoreSerializer::class)
        @SerialName("APP_STORE")
        data class AppStore(
            @SerialName("product_identifier")
            val productIdentifier: String = "",
        ) : StoreProduct()

        @Serializable(with = StripeSerializer::class)
        @SerialName("STRIPE")
        data class Stripe(
            @SerialName("environment")
            val environment: String,
            @SerialName("product_identifier")
            val productId: String,
            @SerialName("trial_days")
            val trialDays: Int,
            @SerialName("meta")
            val meta: JsonObject = JsonObject(emptyMap()),
        ) : StoreProduct()

        @Serializable(with = OtherSerializer::class)
        @SerialName("OTHER")
        data class Other(
            @SerialName("store")
            val storeType: String,
            @SerialName("metadata")
            private val _data: JsonObject = JsonObject(emptyMap()),
        ) : StoreProduct() {
            val data = _data.toMap()
        }
    }

    val fullProductId: String
        get() =
            when (storeProduct) {
                is StoreProduct.PlayStore -> storeProduct.fullIdentifier
                is StoreProduct.AppStore -> storeProduct.productIdentifier
                is StoreProduct.Stripe -> storeProduct.productId
                is StoreProduct.Other -> compositeId
            }
}

object PlayStoreSerializer : KSerializer<CrossplatformProduct.StoreProduct.PlayStore> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PlayStore")

    override fun serialize(
        encoder: Encoder,
        value: CrossplatformProduct.StoreProduct.PlayStore,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("This class can be saved only by Json")
        val jsonObj =
            buildJsonObject {
                put("store", JsonPrimitive("PLAY_STORE"))
                put("product_identifier", JsonPrimitive(value.productIdentifier))
                put("base_plan_identifier", JsonPrimitive(value.basePlanIdentifier))
                val offer =
                    when (val offer = value.offer) {
                        is Offer.Automatic -> JsonObject(mapOf("type" to JsonPrimitive(offer.type)))
                        is Offer.Specified ->
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive(offer.type),
                                    "offer_identifier" to JsonPrimitive(offer.offerIdentifier),
                                ),
                            )
                    }
                put("offer", offer)
            }
        jsonEncoder.encodeJsonElement(jsonObj)
    }

    override fun deserialize(decoder: Decoder): CrossplatformProduct.StoreProduct.PlayStore {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("This class can be loaded only by Json")
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val productIdentifier =
            jsonObject["product_identifier"]?.jsonPrimitive?.content
                ?: jsonObject["product"]
                    ?.jsonObject
                    ?.get("product_identifier")
                    ?.jsonPrimitive
                    ?.content
                ?: throw SerializationException("product_identifier is missing")
        val basePlanIdentifier = jsonObject["base_plan_identifier"]?.jsonPrimitive?.content ?: ""
        val offerJsonObject = jsonObject["offer"] as? JsonObject
        val type = offerJsonObject?.get("type")?.jsonPrimitive?.content

        val offer =
            when (type) {
                "AUTOMATIC" -> Offer.Automatic()
                "SPECIFIED" -> {
                    val offerIdentifier =
                        offerJsonObject["offer_identifier"]?.jsonPrimitive?.content
                            ?: throw SerializationException("offer_identifier is missing")
                    Offer.Specified(offerIdentifier = offerIdentifier)
                }
                else -> Offer.Specified(offerIdentifier = "sw-none")
            }

        return CrossplatformProduct.StoreProduct.PlayStore(productIdentifier, basePlanIdentifier, offer)
    }
}

object AppStoreSerializer : KSerializer<CrossplatformProduct.StoreProduct.AppStore> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AppStore")

    override fun serialize(
        encoder: Encoder,
        value: CrossplatformProduct.StoreProduct.AppStore,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("This class can be saved only by Json")
        val jsonObj =
            buildJsonObject {
                put("store", JsonPrimitive("APP_STORE"))
                put("product_identifier", JsonPrimitive(value.productIdentifier))
            }
        jsonEncoder.encodeJsonElement(jsonObj)
    }

    override fun deserialize(decoder: Decoder): CrossplatformProduct.StoreProduct.AppStore {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("This class can be loaded only by Json")
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val productIdentifier = jsonObject["product_identifier"]?.jsonPrimitive?.content ?: ""

        return CrossplatformProduct.StoreProduct.AppStore(productIdentifier)
    }
}

object StripeSerializer : KSerializer<CrossplatformProduct.StoreProduct.Stripe> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Stripe")

    override fun serialize(
        encoder: Encoder,
        value: CrossplatformProduct.StoreProduct.Stripe,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("This class can be saved only by Json")
        val jsonObj =
            buildJsonObject {
                put("store", JsonPrimitive("STRIPE"))
                put("environment", JsonPrimitive(value.environment))
                put("product_identifier", JsonPrimitive(value.productId))
                put("trial_days", JsonPrimitive(value.trialDays))
                put("meta", value.meta)
            }
        jsonEncoder.encodeJsonElement(jsonObj)
    }

    override fun deserialize(decoder: Decoder): CrossplatformProduct.StoreProduct.Stripe {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("This class can be loaded only by Json")
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val environment =
            jsonObject["environment"]?.jsonPrimitive?.content
                ?: throw SerializationException("environment is missing")
        val productId =
            jsonObject["product_identifier"]?.jsonPrimitive?.content
                ?: throw SerializationException("product_identifier is missing")
        val trialDays = jsonObject["trial_days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val meta = jsonObject["meta"] as? JsonObject ?: JsonObject(emptyMap())

        return CrossplatformProduct.StoreProduct.Stripe(environment, productId, trialDays, meta)
    }
}

object OtherSerializer : KSerializer<CrossplatformProduct.StoreProduct.Other> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Other")

    override fun serialize(
        encoder: Encoder,
        value: CrossplatformProduct.StoreProduct.Other,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("This class can be saved only by Json")
        val jsonObj =
            buildJsonObject {
                put("store", JsonPrimitive("OTHER"))
                put("store", JsonPrimitive(value.storeType))
            }
        jsonEncoder.encodeJsonElement(jsonObj)
    }

    override fun deserialize(decoder: Decoder): CrossplatformProduct.StoreProduct.Other {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("This class can be loaded only by Json")
        val jsonObject = jsonDecoder.decodeJsonElement() as JsonObject

        val storeType = jsonObject["store"]?.jsonPrimitive?.content ?: "OTHER"

        return CrossplatformProduct.StoreProduct.Other(storeType, jsonObject)
    }
}

@Serializer(forClass = CrossplatformProduct::class)
object CrossplatformProductSerializer : KSerializer<CrossplatformProduct> {
    override fun serialize(
        encoder: Encoder,
        value: CrossplatformProduct,
    ) {
        val jsonOutput =
            encoder as? JsonEncoder
                ?: throw SerializationException("This class can be saved only by Json")
        val jsonObject =
            buildJsonObject {
                put("composite_id", JsonPrimitive(value.compositeId))
                put("product_id", JsonPrimitive(value.fullProductId))
                put("store_product", encoder.json.encodeToJsonElement(value.storeProduct))
                put("entitlements", encoder.json.encodeToJsonElement(value.entitlements))
            }
        jsonOutput.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): CrossplatformProduct {
        val jsonInput =
            decoder as? JsonDecoder
                ?: throw SerializationException("This class can be loaded only by Json")
        val jsonObject = jsonInput.decodeJsonElement().jsonObject

        val compositeId =
            jsonObject["composite_id"]?.jsonPrimitive?.content
                ?: jsonObject["sw_composite_product_id"]?.jsonPrimitive?.content ?: ""
        val storeProductJsonObject =
            jsonObject["store_product"]?.jsonObject
                ?: throw SerializationException("Missing store_product")
        val entitlements =
            jsonObject["entitlements"]
                ?.jsonArray
                ?.map {
                    decoder.json.decodeFromJsonElement<Entitlement>(it)
                } ?: emptyList()

        val storeProduct =
            try {
                val storeType = storeProductJsonObject["store"]?.jsonPrimitive?.content
                when (storeType) {
                    "PLAY_STORE" -> decoder.json.decodeFromJsonElement<CrossplatformProduct.StoreProduct.PlayStore>(storeProductJsonObject)
                    "APP_STORE" -> decoder.json.decodeFromJsonElement<CrossplatformProduct.StoreProduct.AppStore>(storeProductJsonObject)
                    "STRIPE" -> decoder.json.decodeFromJsonElement<CrossplatformProduct.StoreProduct.Stripe>(storeProductJsonObject)
                    else -> CrossplatformProduct.StoreProduct.Other(storeType ?: "OTHER", jsonObject)
                }
            } catch (e: SerializationException) {
                Logger.debug(
                    LogLevel.error,
                    LogScope.configManager,
                    "Failed to deserialize product store, falling back to OTHER- ${e.message} for $jsonObject",
                    jsonObject,
                    e,
                )
                // Fallback to Other if deserialization fails
                CrossplatformProduct.StoreProduct.Other("OTHER", jsonObject)
            }

        return CrossplatformProduct(
            compositeId = compositeId,
            storeProduct = storeProduct,
            entitlements = entitlements,
            name = "",
        )
    }
}
