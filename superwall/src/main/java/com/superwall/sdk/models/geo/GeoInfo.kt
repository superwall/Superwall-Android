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
) {
    internal companion object {
        internal fun stub(): GeoInfo =
            GeoInfo(
                city = "NYC",
                country = "USA",
                longitude = 40.7128,
                latitude = -74.0060,
                region = "New York",
                regionCode = "NY",
                continent = "North America",
                metroCode = "501",
                postalCode = "10001",
                timezone = "America/New_York",
            )
    }
}
