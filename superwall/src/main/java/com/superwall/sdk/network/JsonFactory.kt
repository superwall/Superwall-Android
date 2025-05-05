package com.superwall.sdk.network

import com.superwall.sdk.models.paywall.LocalNotificationTypeSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.serializersModuleOf

interface JsonFactory {
    fun json() =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            namingStrategy = JsonNamingStrategy.SnakeCase
            serializersModule =
                serializersModuleOf(
                    LocalNotificationTypeSerializer,
                )
        }
}
