package com.superwall.sdk.store.transactions.notifications

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.paywall.view.SuperwallPaywallActivity
import java.util.concurrent.TimeUnit

internal class NotificationScheduler {
    companion object {
        private const val TAG_PREFIX = "superwall_notification_"

        /**
         * Creates a unique tag for a notification ID to allow cancellation and replacement.
         */
        private fun tagForNotificationId(id: Int): String = "${TAG_PREFIX}$id"

        /**
         * Cancels any pending notification with the given ID.
         */
        fun cancelNotificationById(
            id: Int,
            context: Context,
        ) {
            val workManager = WorkManager.getInstance(context)
            val tag = tagForNotificationId(id)
            workManager.cancelAllWorkByTag(tag)
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallView,
                message = "Cancelled pending notification with tag: $tag",
            )
        }

        fun scheduleNotifications(
            notifications: List<LocalNotification>,
            factory: DeviceHelperFactory,
            context: Context,
            cancelExisting: Boolean = false,
        ) {
            val workManager = WorkManager.getInstance(context)

            notifications.forEach { notification ->
                // Cancel existing notification with the same ID if requested
                if (cancelExisting) {
                    cancelNotificationById(notification.id, context)
                }

                val data =
                    workDataOf(
                        "id" to notification.id,
                        "title" to notification.title,
                        "body" to notification.body,
                        "subtitle" to notification.subtitle,
                    )

                var delay = notification.delay // delay in milliseconds

                val isSandbox = factory.makeIsSandbox()
                if (isSandbox) {
                    delay = delay / 24 / 60
                }

                if (delay <= 0) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallView,
                        message =
                            "Notification delay isn't greater than 0 milliseconds. " +
                                "Notifications will not be scheduled.",
                    )
                    return
                }

                val idTag = tagForNotificationId(notification.id)

                val notificationWork =
                    OneTimeWorkRequestBuilder<NotificationWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(data)
                        .addTag(SuperwallPaywallActivity.NOTIFICATION_CHANNEL_ID)
                        .addTag(idTag) // Add ID-specific tag for cancellation
                        .build()

                workManager.enqueue(notificationWork)

                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallView,
                    message = "Scheduled notification with tag: $idTag, delay: ${delay}ms",
                )
            }
        }
    }
}
