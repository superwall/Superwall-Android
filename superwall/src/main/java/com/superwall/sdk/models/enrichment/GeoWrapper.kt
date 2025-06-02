package com.superwall.sdk.models.enrichment

import com.superwall.sdk.models.SerializableEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeoWrapper(
    @SerialName("geoInfo")
    val info: GeoInfo,
) : SerializableEntity
