package com.superwall.sdk.permissions

import android.app.Activity

/**
 * Interface for managing user permissions in a testable way
 */
interface UserPermissions {
    /**
     * Check if a specific permission is granted
     * @param permission The permission to check
     * @return true if the permission is granted, false otherwise
     */
    fun hasPermission(permission: CommonPermission): Boolean

    /**
     * Request a specific permission from the user
     * @param activity The activity to use for the permission request
     * @param permission The permission to request
     * @param callback Callback to receive the result of the permission request
     */
    suspend fun requestPermission(
        activity: Activity,
        permission: CommonPermission,
        callback: (PermissionResult) -> Unit = {},
    ): PermissionResult

    /**
     * Request multiple permissions at once
     * @param activity The activity to use for the permission request
     * @param permissions The permissions to request
     * @param callback Callback to receive the results of the permission requests
     */
    suspend fun requestPermissions(
        activity: Activity,
        permissions: List<CommonPermission>,
        callback: (Map<CommonPermission, PermissionResult>) -> Unit = {},
    ): Map<CommonPermission, PermissionResult>
}

/**
 * Result of a permission request
 */
sealed class PermissionResult {
    /**
     * Permission was granted
     */
    object Granted : PermissionResult()

    /**
     * Permission was denied
     */
    object Denied : PermissionResult()

    /**
     * Permission was denied and "Don't ask again" was selected
     */
    object DeniedPermanently : PermissionResult()

    /**
     * Permission request failed due to an error
     */
    data class Error(
        val exception: Exception,
    ) : PermissionResult()
}
