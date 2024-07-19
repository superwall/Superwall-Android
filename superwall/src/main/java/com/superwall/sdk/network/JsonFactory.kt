package com.superwall.sdk.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

interface JsonFactory {
    fun json() =
        Json {
            encodeDefaults = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
}
