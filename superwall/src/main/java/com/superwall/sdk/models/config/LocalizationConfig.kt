package com.superwall.sdk.models.config

import kotlinx.serialization.Serializable

@Serializable
internal data class LocalizationConfig(
    var locales: List<LocaleConfig>
) {
    @Serializable
    internal data class LocaleConfig(
        var locale: String
    )
}
