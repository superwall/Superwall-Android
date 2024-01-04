package com.superwall.sdk.store.transactions.notifications

import Logger
import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import java.util.concurrent.TimeUnit

class NotificationScheduler(
    val context: Context
) {
    companion object {
        fun scheduleNotifications(
            notifications: List<LocalNotification>,
            factory: DeviceHelperFactory,
            context: Context
        ) {
            if (notifications.isEmpty()) return

            val workManager = WorkManager.getInstance(context)

            notifications.forEach { notification ->
                val data = workDataOf(
                    "id" to notification.id,
                    "title" to notification.title,
                    "body" to notification.body
                )

                var delay = notification.delay // delay in milliseconds

                val isSandbox = factory.makeIsSandbox()
                if (isSandbox) {
                    delay = delay / 24 / 60
                }

                if (delay <= 0) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallViewController,
                        message = "Notification delay isn't greater than 0 milliseconds. " +
                                "Notifications will not be scheduled."
                    )
                    return
                }

                val notificationWork = OneTimeWorkRequestBuilder<NotificationWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build()

                workManager.enqueue(notificationWork)
            }
        }
    }
}
