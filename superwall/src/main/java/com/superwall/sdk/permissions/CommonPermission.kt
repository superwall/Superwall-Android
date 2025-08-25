package com.superwall.sdk.permissions

import android.Manifest

/**
 * Common Android permissions that can be requested through the Superwall SDK
 */
enum class CommonPermission(
    val rawValue: String,
) {
    CAMERA(Manifest.permission.CAMERA),
    MICROPHONE(Manifest.permission.RECORD_AUDIO),
    READ_EXTERNAL_STORAGE(Manifest.permission.READ_EXTERNAL_STORAGE),
    WRITE_EXTERNAL_STORAGE(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    ACCESS_FINE_LOCATION(Manifest.permission.ACCESS_FINE_LOCATION),
    ACCESS_COARSE_LOCATION(Manifest.permission.ACCESS_COARSE_LOCATION),
    ACCESS_BACKGROUND_LOCATION(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    BLUETOOTH(Manifest.permission.BLUETOOTH),
    READ_CONTACTS(Manifest.permission.READ_CONTACTS),
    WRITE_CONTACTS(Manifest.permission.WRITE_CONTACTS),
    READ_CALENDAR(Manifest.permission.READ_CALENDAR),
    WRITE_CALENDAR(Manifest.permission.WRITE_CALENDAR),
    ;

    companion object {
        /**
         * Find a CommonPermission by its raw Android permission string
         * @param raw The Android permission string (e.g., "android.permission.CAMERA")
         * @return The corresponding CommonPermission or null if not found
         */
        fun fromRaw(raw: String): CommonPermission? = values().find { it.rawValue == raw }

        /**
         * Find a CommonPermission by its enum name (case insensitive)
         * @param name The enum name (e.g., "CAMERA", "camera")
         * @return The corresponding CommonPermission or null if not found
         */
        fun fromName(name: String): CommonPermission? = values().find { it.name.equals(name, ignoreCase = true) }
    }
}
