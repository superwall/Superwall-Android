package com.superwall.sdk.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Implementation of UserPermissions using Android's permission system
 */
internal class UserPermissionsImpl(
    private val context: Context,
) : UserPermissions {
    private val pendingRequests = mutableMapOf<Int, PermissionRequestData>()
    private var requestIdCounter = 1000

    override fun hasPermission(permission: CommonPermission): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            permission.rawValue,
        ) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestPermission(
        activity: Activity,
        permission: CommonPermission,
        callback: (PermissionResult) -> Unit,
    ): PermissionResult {
        // If already granted, return immediately
        if (hasPermission(permission)) {
            val result = PermissionResult.Granted
            callback(result)
            return result
        }

        return suspendCancellableCoroutine { continuation ->
            val requestId = requestIdCounter++

            pendingRequests[requestId] =
                PermissionRequestData(
                    permissions = listOf(permission),
                    onResult = { results ->
                        val result =
                            results[permission] ?: PermissionResult.Error(
                                IllegalStateException("Permission result not found"),
                            )
                        callback(result)
                        continuation.resume(result)
                    },
                )

            try {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(permission.rawValue),
                    requestId,
                )
            } catch (e: Exception) {
                pendingRequests.remove(requestId)
                val error = PermissionResult.Error(e)
                callback(error)
                continuation.resume(error)
            }
        }
    }

    override suspend fun requestPermissions(
        activity: Activity,
        permissions: List<CommonPermission>,
        callback: (Map<CommonPermission, PermissionResult>) -> Unit,
    ): Map<CommonPermission, PermissionResult> {
        // Check which permissions are already granted
        val alreadyGranted = permissions.filter { hasPermission(it) }
        val needToRequest = permissions.filter { !hasPermission(it) }

        val results = mutableMapOf<CommonPermission, PermissionResult>()

        // Add already granted permissions to results
        alreadyGranted.forEach { permission ->
            results[permission] = PermissionResult.Granted
        }

        // If no permissions need to be requested, return immediately
        if (needToRequest.isEmpty()) {
            callback(results)
            return results
        }

        return suspendCancellableCoroutine { continuation ->
            val requestId = requestIdCounter++

            pendingRequests[requestId] =
                PermissionRequestData(
                    permissions = needToRequest,
                    onResult = { requestResults ->
                        results.putAll(requestResults)
                        callback(results)
                        continuation.resume(results)
                    },
                )

            try {
                ActivityCompat.requestPermissions(
                    activity,
                    needToRequest.map { it.rawValue }.toTypedArray(),
                    requestId,
                )
            } catch (e: Exception) {
                pendingRequests.remove(requestId)
                needToRequest.forEach { permission ->
                    results[permission] = PermissionResult.Error(e)
                }
                callback(results)
                continuation.resume(results)
            }
        }
    }

    /**
     * Handle permission request results from the activity
     * This should be called from the activity's onRequestPermissionsResult method
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        val requestData = pendingRequests.remove(requestCode) ?: return

        val results = mutableMapOf<CommonPermission, PermissionResult>()

        for (i in permissions.indices) {
            val permissionString = permissions[i]
            val grantResult = grantResults.getOrNull(i) ?: PackageManager.PERMISSION_DENIED

            val commonPermission = CommonPermission.fromRaw(permissionString)
            if (commonPermission != null) {
                val result =
                    when (grantResult) {
                        PackageManager.PERMISSION_GRANTED -> PermissionResult.Granted
                        PackageManager.PERMISSION_DENIED -> {
                            // Check if it's permanently denied
                            val activity = context as? Activity
                            if (activity != null &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permissionString)
                            ) {
                                PermissionResult.DeniedPermanently
                            } else {
                                PermissionResult.Denied
                            }
                        }
                        else -> PermissionResult.Denied
                    }
                results[commonPermission] = result
            }
        }

        requestData.onResult(results)
    }

    private data class PermissionRequestData(
        val permissions: List<CommonPermission>,
        val onResult: (Map<CommonPermission, PermissionResult>) -> Unit,
    )
}
