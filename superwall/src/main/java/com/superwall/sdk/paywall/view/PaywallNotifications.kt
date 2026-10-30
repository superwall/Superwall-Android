package com.superwall.sdk.paywall.view

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.permissions.requestPermissionFrom
import com.superwall.sdk.store.transactions.notifications.NotificationScheduler
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Asks for the notification permission and schedules a paywall's local notifications, for
 * whichever host shows the paywall: a [SuperwallPaywallActivity], an [InWindowPaywallHost], or
 * the app's own Activity it is embedded in.
 *
 * [requestPermission] shows the system permission prompt and reports the user's answer through
 * the callback it's given, or later through [onPermissionResult]. A grant that arrives after the
 * waiting caller was cancelled still schedules, once.
 */
internal class PaywallNotifications(
    activity: Activity,
    private val requestPermission: (onResult: (granted: Boolean) -> Unit) -> Unit,
) {
    companion object {
        private const val NOTIFICATION_CHANNEL_NAME = "Trial Reminder Notifications"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION =
            "Notifications sent when a free trial is about to end."

        /**
         * The notifications of the host showing [paywallView]: its in-window host, else its
         * encapsulating activity (or [fallbackActivity]) when that is a [SuperwallPaywallActivity],
         * else the app's own Activity it is embedded in, e.g. by `PaywallComposable`.
         */
        fun forPaywall(
            paywallView: PaywallView?,
            fallbackActivity: Activity? = null,
        ): PaywallNotifications? {
            paywallView?.inWindowHost?.let { host -> return host.notifications.takeUnless { host.isClosed } }
            val activity = paywallView?.encapsulatingActivity?.get() ?: fallbackActivity
            (activity as? SuperwallPaywallActivity)?.let { return it.notifications }
            val embeddingActivity =
                paywallView?.encapsulatingActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                    ?: return null
            return paywallView.embeddedNotifications(embeddingActivity)
        }

        /**
         * Notifications for a paywall embedded in the app's own [activity]. The permission is
         * asked for the same way an [InWindowPaywallHost] asks, which works for any Activity. A
         * pending request is released when a lifecycle-aware [activity] is destroyed, since its
         * answer can no longer arrive.
         */
        fun forEmbeddingActivity(activity: Activity): PaywallNotifications {
            val notifications =
                PaywallNotifications(activity) { onResult ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread onResult(false)
                        requestPermissionFrom(activity, Manifest.permission.POST_NOTIFICATIONS, onResult)
                    }
                }
            (activity as? LifecycleOwner)?.lifecycle?.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) = notifications.release()
                },
            )
            return notifications
        }
    }

    private val activityRef = WeakReference(activity)
    private var permissionCallback: ((Boolean) -> Unit)? = null

    val activity: Activity?
        get() = activityRef.get()

    suspend fun attemptToSchedule(
        notifications: List<LocalNotification>,
        factory: DeviceHelperFactory,
        cancelExisting: Boolean,
        applySandboxScaling: Boolean,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val activity = activityRef.get()
        if (notifications.isEmpty() || activity == null) {
            continuation.resume(Unit) // Resume immediately as there's nothing to schedule
            return@suspendCancellableCoroutine
        }

        createNotificationChannel(activity)
        val permissionRequestedAt = SystemClock.elapsedRealtime()
        // A replacement request must release the previous waiter, too.
        permissionCallback?.invoke(false)
        val callback =
            object : (Boolean) -> Unit {
                override fun invoke(granted: Boolean) {
                    if (permissionCallback === this) permissionCallback = null
                    try {
                        if (granted) {
                            scheduleGrantedNotifications(
                                activity,
                                notifications,
                                factory,
                                cancelExisting,
                                applySandboxScaling,
                                permissionRequestedAt,
                            )
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        permissionCallback = callback
        try {
            checkAndRequestNotificationPermissions(activity) { granted ->
                if (permissionCallback === callback) callback(granted)
            }
        } catch (e: Exception) {
            if (permissionCallback === callback) permissionCallback = null
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    /** Delivers the answer to the pending permission request, if any. */
    fun onPermissionResult(granted: Boolean) {
        permissionCallback?.invoke(granted)
    }

    /** Releases a pending permission waiter without scheduling, e.g. when the host goes away. */
    fun release() = onPermissionResult(false)

    private fun scheduleGrantedNotifications(
        context: Context,
        notifications: List<LocalNotification>,
        factory: DeviceHelperFactory,
        cancelExisting: Boolean,
        applySandboxScaling: Boolean,
        permissionRequestedAt: Long,
    ) {
        // Web delays are anchored to checkout, so permission wait must not shift them.
        val readyNotifications =
            if (applySandboxScaling) {
                notifications
            } else {
                val elapsed = SystemClock.elapsedRealtime() - permissionRequestedAt
                notifications.mapNotNull { it.copy(delay = it.delay - elapsed).takeIf { reminder -> reminder.delay > 0 } }
            }
        if (readyNotifications.isEmpty()) return
        NotificationScheduler.scheduleNotifications(
            notifications = readyNotifications,
            factory = factory,
            context = context,
            cancelExisting = cancelExisting,
            applySandboxScaling = applySandboxScaling,
        )
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel =
                NotificationChannel(
                    SuperwallPaywallActivity.NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    importance,
                ).apply {
                    description = NOTIFICATION_CHANNEL_DESCRIPTION
                }
            channel.setShowBadge(false)
            // Register the channel with the system
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun checkAndRequestNotificationPermissions(
        activity: Activity,
        callback: (Boolean) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                ) {
                    // First time asking or user previously denied without 'Don't ask again'
                    requestPermission(callback)
                } else {
                    // Permission previously denied with 'Don't ask again'
                    callback(false)
                }
            } else {
                callback(true)
            }
        } else {
            callback(areNotificationsEnabled(activity))
        }
    }

    private fun areNotificationsEnabled(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(SuperwallPaywallActivity.NOTIFICATION_CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
