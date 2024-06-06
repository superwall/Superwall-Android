package com.superwall.sdk.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

fun getSWJson(): Json =
    Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

interface SerializableEntity
