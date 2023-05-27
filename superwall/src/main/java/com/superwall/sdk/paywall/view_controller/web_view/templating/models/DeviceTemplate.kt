package com.superwall.sdk.paywall.view_controller.web_view.templating.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class DeviceTemplate(
    val publicApiKey: String,
    val platform: String,
    val appUserId: String,
    val aliases: List<String>,
    val vendorId: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val deviceLocale: String,
    val deviceLanguageCode: String,
    val deviceCurrencyCode: String,
    val deviceCurrencySymbol: String,
    val timezoneOffset: Int,
    val radioType: String,
    val interfaceStyle: String,
    val isLowPowerModeEnabled: Boolean,
    val bundleId: String,
    val appInstallDate: String,
    val isMac: Boolean,
    val daysSinceInstall: Int,
    val minutesSinceInstall: Int,
    val daysSinceLastPaywallView: Int?,
    val minutesSinceLastPaywallView: Int?,
    val totalPaywallViews: Int,
    val utcDate: String,
    val localDate: String,
    val utcTime: String,
    val localTime: String,
    val utcDateTime: String,
    val localDateTime: String,
    val isSandbox: String,
    val subscriptionStatus: String,
    val isFirstAppOpen: Boolean
) {
    fun toDictionary(): Map<String, Any> {
        val json = Json { encodeDefaults = true }
        val jsonString = json.encodeToString(this)
        return json.parseToJsonElement(jsonString).jsonObject.toMap()
    }
}
