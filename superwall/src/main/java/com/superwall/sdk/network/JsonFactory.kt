package com.superwall.sdk.network

import com.superwall.sdk.models.paywall.LocalNotificationTypeSerializer
import com.superwall.sdk.models.serialization.DateSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.util.Date

interface JsonFactory {
    fun json() =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            namingStrategy = JsonNamingStrategy.SnakeCase
            serializersModule =
                SerializersModule {
                    LocalNotificationTypeSerializer
                    contextual(Date::class, DateSerializer)
                }
        }
}
