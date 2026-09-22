package com.superwall.sdk.store.transactions.notifications

import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.paywall.presentation.PaywallInfo

internal object TrialReminderLogic {
    /**
     * Builds the fallback trial reminders to schedule after a paywall purchase completes.
     *
     * Reminders are only meaningful when a free trial actually started. A purchase that
     * charges immediately (no trial offer, or the user already consumed their trial) must not
     * schedule a "your free trial ends" notification, even if the paywall has trial reminders
     * configured.
     */
    fun fallbackTrialNotifications(
        paywallInfo: PaywallInfo,
        didStartFreeTrial: Boolean,
    ): List<LocalNotification> {
        if (!didStartFreeTrial) return emptyList()
        return paywallInfo.localNotifications
            .filter { it.type == LocalNotificationType.TrialStarted }
            .map { it.copy(id = "${paywallInfo.identifier}_${it.type.raw}") }
    }
}
