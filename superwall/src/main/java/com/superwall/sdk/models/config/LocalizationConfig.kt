package com.superwall.sdk.models.config

import kotlinx.serialization.Serializable

@Serializable
data class LocalizationConfig(
    var locales: List<LocaleConfig>,
) {
    @Serializable
    data class LocaleConfig(
        var locale: String,
    )
}
