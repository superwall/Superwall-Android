package com.superwall.sdk.permissions

import android.Manifest
import android.os.Build

/**
 * Permission types that can be requested from the host app.
 * Maps to the paywall schema permission_type values.
 */
enum class PermissionType(
    val rawValue: String,
) {
    NOTIFICATION("notification"),
    ;

    /**
     * Get the Android manifest permission string for this permission type.
     * Returns null if the permission is not available on the current API level.
     */
    fun toManifestPermission(): String? =
        when (this) {
            NOTIFICATION ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.POST_NOTIFICATIONS
                } else {
                    null // Notifications don't require runtime permission before API 33
                }
        }

    companion object {
        /**
         * Find a PermissionType by its raw value from the paywall schema
         * @param raw The permission type string (e.g., "notification")
         * @return The corresponding PermissionType or null if not found
         */
        fun fromRaw(raw: String): PermissionType? = values().find { it.rawValue == raw }
    }
}
