package com.superwall.sdk.store.transactions.notifications

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.paywall.presentation.PaywallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for trial reminders being scheduled after purchases that did not start a
 * free trial (reported against 2.7.13: "Your free trial ends…" shown after buying a
 * subscription with no trial on a paywall with Trial Reminders enabled).
 */
class TrialReminderLogicTest {
    private val trialReminder =
        LocalNotification(
            id = "dashboard-id",
            type = LocalNotificationType.TrialStarted,
            title = "Your free trial ends soon",
            body = "Your trial ends in 2 days.",
            delay = 5L * 24 * 60 * 60 * 1000,
        )

    private fun paywallInfo(notifications: List<LocalNotification>) =
        PaywallInfo.empty().copy(
            identifier = "paywall_abc",
            localNotifications = notifications,
        )

    @Test
    fun noTrialStarted_schedulesNothingEvenWhenRemindersAreConfigured() {
        Given("a paywall with a trial reminder configured and a purchase that charged immediately") {
            val info = paywallInfo(listOf(trialReminder))

            When("fallback reminders are computed") {
                val result =
                    TrialReminderLogic.fallbackTrialNotifications(
                        paywallInfo = info,
                        didStartFreeTrial = false,
                    )

                Then("no reminder is scheduled") {
                    assertTrue(result.isEmpty())
                }
            }
        }
    }

    @Test
    fun trialStarted_schedulesConfiguredRemindersWithPaywallScopedIds() {
        Given("a paywall with a trial reminder configured and a purchase that started a trial") {
            val info = paywallInfo(listOf(trialReminder))

            When("fallback reminders are computed") {
                val result =
                    TrialReminderLogic.fallbackTrialNotifications(
                        paywallInfo = info,
                        didStartFreeTrial = true,
                    )

                Then("the reminder is scheduled under a paywall-scoped id") {
                    assertEquals(1, result.size)
                    val scheduled = result.single()
                    assertEquals("paywall_abc_TRIAL_STARTED", scheduled.id)
                    assertEquals(trialReminder.title, scheduled.title)
                    assertEquals(trialReminder.body, scheduled.body)
                    assertEquals(trialReminder.delay, scheduled.delay)
                }
            }
        }
    }

    @Test
    fun trialStarted_ignoresNonTrialNotificationTypes() {
        Given("a paywall whose only notifications are of an unsupported type") {
            val info =
                paywallInfo(
                    listOf(trialReminder.copy(type = LocalNotificationType.Unsupported)),
                )

            When("fallback reminders are computed for a started trial") {
                val result =
                    TrialReminderLogic.fallbackTrialNotifications(
                        paywallInfo = info,
                        didStartFreeTrial = true,
                    )

                Then("nothing is scheduled") {
                    assertTrue(result.isEmpty())
                }
            }
        }
    }

    @Test
    fun trialStarted_withoutConfiguredReminders_schedulesNothing() {
        Given("a paywall without trial reminders") {
            val info = paywallInfo(emptyList())

            When("fallback reminders are computed for a started trial") {
                val result =
                    TrialReminderLogic.fallbackTrialNotifications(
                        paywallInfo = info,
                        didStartFreeTrial = true,
                    )

                Then("nothing is scheduled") {
                    assertTrue(result.isEmpty())
                }
            }
        }
    }
}
