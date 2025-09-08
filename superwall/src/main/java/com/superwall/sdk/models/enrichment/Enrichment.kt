package com.superwall.sdk.models.enrichment

import com.superwall.sdk.storage.core_data.convertFromJsonElement
import com.superwall.sdk.storage.core_data.convertToJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

@Serializable
data class Enrichment(
    @SerialName("user")
    private val _user: JsonObject,
    @SerialName("device")
    private val _device: JsonObject,
) {
    companion object {
        fun stub() =
            Enrichment(
                JsonObject(emptyMap()),
                JsonObject(mapOf("demandTier" to "silver".convertToJsonElement())),
            )
    }

    val user: Map<String, Any>
        get() = _user.filterValues { it is JsonNull } .mapNotNull { it.key to it.value.convertFromJsonElement()!! }.toMap()

    val device: Map<String,Any>
        get() = _device.filterValues { it is JsonNull }.mapNotNull { it.key to it.value.convertFromJsonElement()!! }.toMap()

}