package com.superwall.sdk.models.serialization

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

inline fun <reified T> String.jsonStringToType(): T {
    val gson = Gson()
    return gson.fromJson(this, object : TypeToken<T>() {}.type)
}
