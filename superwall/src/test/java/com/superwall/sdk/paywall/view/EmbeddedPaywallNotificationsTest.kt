package com.superwall.sdk.paywall.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.view.webview.SendPaywallMessages
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessage
import com.superwall.sdk.store.transactions.notifications.NotificationScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

/** A paywall embedded in the app's own ComponentActivity, as `PaywallComposable` shows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmbeddedPaywallNotificationsTest {
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private lateinit var paywallView: PaywallView

    private val trialReminders =
        listOf(LocalNotification("trial", LocalNotificationType.TrialStarted, "Trial ending", body = "Reminder", delay = 86_400_000L))
    private val deviceHelperFactory = mockk<DeviceHelperFactory>()

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()

        val factory = mockk<PaywallView.Factory>(relaxed = true)
        every { factory.makeSuperwallOptions() } returns SuperwallOptions()
        every { factory.delegate() } returns mockk(relaxed = true)
        every { factory.updatePaywallInfo(any()) } just Runs
        every { factory.getCurrentUserAttributes() } returns emptyMap()
        coEvery { factory.track(any()) } returns Result.success(mockk<TrackingResult>(relaxed = true))
        coEvery { factory.storePresentationObject(any(), any()) } just Runs

        paywallView =
            PaywallView(
                context = activity,
                deviceHelper = mockk(relaxed = true),
                factory = factory,
                storage = mockk(relaxed = true),
                webView = InWindowPaywallHostTest.FakeWebUI(mockk(relaxed = true)),
                cache = null,
                controller = PaywallView.PaywallController(PaywallViewState(paywall = Paywall.stub(), locale = "en-US")),
                sendMessages =
                    object : SendPaywallMessages {
                        override fun handle(message: PaywallMessage) = Unit
                    },
                redeemer = mockk(),
            )

        mockkObject(NotificationScheduler.Companion)
        every { NotificationScheduler.scheduleNotifications(any(), any(), any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(NotificationScheduler.Companion)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** What `PaywallBuilder.activity(...)` does for `PaywallComposable`. */
    private fun embed() {
        paywallView.encapsulatingActivity = WeakReference(activity)
    }

    @Test
    fun notifications_ofAnEmbeddedPaywallAreScheduledInItsActivity() =
        runTest {
            Given("a paywall embedded in the app's Activity and a granted notification permission") {
                shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                embed()

                When("the paywall's notifications are looked up and scheduled") {
                    val notifications = PaywallNotifications.forPaywall(paywallView, activity)
                    notifications?.attemptToSchedule(trialReminders, deviceHelperFactory, cancelExisting = false, applySandboxScaling = true)

                    Then("they are scheduled instead of being skipped, and later lookups reuse them") {
                        assertNotNull(notifications)
                        assertSame(notifications, PaywallNotifications.forPaywall(paywallView, activity))
                        verify(exactly = 1) {
                            NotificationScheduler.scheduleNotifications(trialReminders, deviceHelperFactory, activity, false, true)
                        }
                    }
                }
            }
        }

    @Test
    fun notificationPermission_grantedAfterTheEmbeddedPaywallIsGoneStillSchedules() =
        runTest {
            Given("a paywall embedded in the app's Activity without the notification permission") {
                shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                embed()

                When("its notifications are scheduled") {
                    val job =
                        launch {
                            PaywallNotifications
                                .forPaywall(paywallView, activity)!!
                                .attemptToSchedule(trialReminders, deviceHelperFactory, cancelExisting = false, applySandboxScaling = true)
                        }
                    runCurrent()
                    idleMain()
                    val request = shadowOf(activity).lastRequestedPermission

                    Then("the Activity asks for the permission through its registry") {
                        assertFalse(job.isCompleted)
                        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
                    }

                    When("the paywall is released, as PaywallComposable does after a purchase, and the user then grants it") {
                        paywallView.encapsulatingActivity = null
                        paywallView.cleanup()
                        @Suppress("DEPRECATION")
                        activity.onRequestPermissionsResult(
                            request.requestCode,
                            request.requestedPermissions,
                            intArrayOf(PackageManager.PERMISSION_GRANTED),
                        )
                        runCurrent()

                        Then("the notifications are scheduled") {
                            assertTrue(job.isCompleted)
                            verify(exactly = 1) {
                                NotificationScheduler.scheduleNotifications(trialReminders, deviceHelperFactory, activity, false, true)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun notificationPermission_pendingWhenTheActivityIsDestroyedIsReleased() =
        runTest {
            Given("an embedded paywall waiting on the notification permission") {
                shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                embed()
                val job =
                    launch {
                        PaywallNotifications
                            .forPaywall(paywallView, activity)!!
                            .attemptToSchedule(trialReminders, deviceHelperFactory, cancelExisting = false, applySandboxScaling = true)
                    }
                runCurrent()
                idleMain()

                When("the Activity is destroyed before the user answers") {
                    controller.pause().stop().destroy()
                    runCurrent()

                    Then("the waiter is released and nothing is scheduled") {
                        assertTrue(job.isCompleted)
                        verify(exactly = 0) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any(), any()) }
                    }
                }
            }
        }

    @Test
    fun notifications_ofAPaywallWithNoHostAreNotLookedUpFromTheCurrentActivity() {
        Given("a paywall that is not embedded anywhere") {
            When("its notifications are looked up with the app's Activity as the fallback") {
                val notifications = PaywallNotifications.forPaywall(paywallView, activity)

                Then("there are none, since the fallback only stands in for a SuperwallPaywallActivity") {
                    assertNull(notifications)
                }
            }
        }
    }
}
