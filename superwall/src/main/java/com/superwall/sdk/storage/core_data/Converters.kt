package com.superwall.sdk.storage.core_data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromMap(map: Map<String, Any>): String {
        val sanitizedMap = sanitizeMap(map)
        return gson.toJson(sanitizedMap)
    }

    @TypeConverter
    fun toMap(json: String): Map<String, Any> {
        return gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
    }

    @TypeConverter
    fun toDate(timestamp: Long): Date {
        return Date(timestamp)
    }

    @TypeConverter
    fun toTimestamp(date: Date): Long {
        return date.time
    }

    /**
     * Filters out any values that can't be saved to the database.
     */
    fun sanitizeMap(map: Map<String, Any>): Map<String, Any> {
        return map.filterValues { value ->
            when (value) {
                is String, is Number, is Boolean, is List<*>, is Map<*, *> -> true
                else -> false
            }
        }
    }
}