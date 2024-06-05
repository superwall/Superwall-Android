package com.superwall.sdk.models.geo

import kotlinx.serialization.Serializable

@Serializable
data class GeoInfo(
    val city: String?,
    val country: String?,
    val longitude: Double?,
    val latitude: Double?,
    val region: String?,
    val regionCode: String?,
    val continent: String?,
    val metroCode: String?,
    val postalCode: String?,
    val timezone: String?,
)
