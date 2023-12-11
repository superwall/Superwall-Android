package com.superwall.sdk.models.product

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class ProductType {
    @SerialName("primary")
    PRIMARY,

    @SerialName("secondary")
    SECONDARY,

    @SerialName("tertiary")
    TERTIARY;

    override fun toString() = name.lowercase()
}

@Serializable(with = ProductSerializer::class)
data class Product(
    val type: ProductType,
    val id: String
)

@Serializer(forClass = Product::class)
object ProductSerializer : KSerializer<Product> {
    override fun serialize(encoder: Encoder, value: Product) {
        // Create a JSON object with custom field names for serialization
        val jsonOutput = encoder as? JsonEncoder
            ?: throw SerializationException("This class can be saved only by Json")
        val jsonObject = buildJsonObject {
            put("product", Json.encodeToJsonElement(ProductType.serializer(), value.type))
            put("productId", JsonPrimitive(value.id))
        }
        // Encode the JSON object
        jsonOutput.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): Product {
        // Decode the JSON object
        val jsonInput = decoder as? JsonDecoder
            ?: throw SerializationException("This class can be loaded only by Json")
        val jsonObject = jsonInput.decodeJsonElement().jsonObject

        // Extract fields using the expected names during deserialization
        val type = Json.decodeFromJsonElement(ProductType.serializer(), jsonObject["product"]!!)
        val id = jsonObject["product_id_android"]?.jsonPrimitive?.content ?: ""

        return Product(type, id)
    }
}