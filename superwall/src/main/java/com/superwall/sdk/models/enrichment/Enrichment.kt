package com.superwall.sdk.models.enrichment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Enrichment(
    @SerialName("user")
    val user: JsonObject,
    @SerialName("device")
    val device: JsonObject,
)
