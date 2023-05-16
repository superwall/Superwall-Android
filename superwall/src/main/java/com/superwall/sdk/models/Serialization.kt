package com.superwall.sdk.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

fun getSWJson(): Json {
    return Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
    }
}