package com.superwall.sdk.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Requests runtime permissions on behalf of an Activity that can't receive the answer itself:
 * a plain [Activity], such as a game engine's, gets results in its own
 * `onRequestPermissionsResult`. This headless, translucent activity asks for the permissions,
 * reports whether all were granted through an in-process callback keyed by request id, and
 * finishes. Destroyed without an answer, the request completes as denied.
 */
internal class PermissionRequestActivity : Activity() {
    companion object {
        private const val EXTRA_REQUEST_ID = "com.superwall.sdk.permissions.REQUEST_ID"
        private const val EXTRA_PERMISSIONS = "com.superwall.sdk.permissions.PERMISSIONS"
        private const val STATE_REQUESTED = "requested"
        private const val REQUEST_CODE = 1

        private val ids = AtomicLong(0)
        private val callbacks = ConcurrentHashMap<Long, (Boolean) -> Unit>()

        /**
         * Asks for [permissions] from a new [PermissionRequestActivity] started by [context],
         * then calls [onResult] on the main thread with whether all were granted. Returns a
         * function that cancels the request, after which [onResult] is never called.
         */
        fun request(
            context: Context,
            permissions: Array<String>,
            onResult: (granted: Boolean) -> Unit,
        ): () -> Unit {
            val id = ids.incrementAndGet()
            callbacks[id] = onResult
            val intent =
                Intent(context, PermissionRequestActivity::class.java)
                    .putExtra(EXTRA_REQUEST_ID, id)
                    .putExtra(EXTRA_PERMISSIONS, permissions)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Logger.debug(
                    LogLevel.error,
                    LogScope.paywallView,
                    "Unable to request permissions ${permissions.joinToString()}",
                    error = e,
                )
                complete(id, false)
            }
            return { callbacks.remove(id) }
        }

        private fun complete(
            id: Long,
            granted: Boolean,
        ) {
            callbacks.remove(id)?.invoke(granted)
        }
    }

    private val requestId: Long
        get() = intent.getLongExtra(EXTRA_REQUEST_ID, -1)

    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean(STATE_REQUESTED) ?: false
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (permissions.isNullOrEmpty() || !callbacks.containsKey(requestId)) {
            // Nothing to ask, or the request is gone (cancelled, or the process was restarted).
            finish()
            return
        }
        // A recreated activity still receives the answer to the request it already made.
        if (!requested) {
            requested = true
            requestPermissions(permissions, REQUEST_CODE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REQUESTED, requested)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        // An empty result means the request was interrupted.
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        complete(requestId, granted)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            complete(requestId, false)
        }
    }
}

private val registryRequestKeys = AtomicLong(0)

/**
 * Asks for [permission] from [activity] and calls [onResult] with the user's answer. A
 * [ComponentActivity] asks through its activity result registry, registered without a lifecycle
 * since the activity is usually already started; a plain [Activity] asks through a
 * [PermissionRequestActivity]. Returns a function that cancels the request.
 */
internal fun requestPermissionFrom(
    activity: Activity,
    permission: String,
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    if (activity !is ComponentActivity) {
        return PermissionRequestActivity.request(activity, arrayOf(permission), onResult)
    }
    var launcher: ActivityResultLauncher<String>? = null
    launcher =
        activity.activityResultRegistry.register(
            "superwall_permission_${registryRequestKeys.incrementAndGet()}",
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            launcher?.unregister()
            onResult(granted)
        }
    try {
        launcher.launch(permission)
    } catch (e: Exception) {
        launcher.unregister()
        Logger.debug(
            LogLevel.error,
            LogScope.paywallView,
            "Unable to request permission $permission",
            error = e,
        )
        onResult(false)
    }
    return { launcher.unregister() }
}
