package com.superwall.sdk.paywall.view

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.store.transactions.notifications.NotificationScheduler
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrialNotificationPermissionTest {
    private val notifications =
        listOf(LocalNotification("trial", LocalNotificationType.TrialStarted, "Trial ending", body = "Reminder", delay = 86_400_000L))
    private val factory = mockk<DeviceHelperFactory>()

    @Before
    fun setup() {
        mockkObject(NotificationScheduler.Companion)
        every { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(NotificationScheduler.Companion)
    }

    @Test
    fun `permission wait is subtracted from absolute web reminder delay`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            every { NotificationScheduler.scheduleNotifications(any(), any(), any(), any(), any()) } just Runs
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            val job = launch { activity.attemptToScheduleNotifications(notifications, factory, false, false) }
            runCurrent()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            job.join()
            verify(exactly = 1) {
                NotificationScheduler.scheduleNotifications(
                    listOf(notifications.single().copy(delay = 86_390_000L)),
                    factory,
                    activity,
                    false,
                    false,
                )
            }
        }

    @Test
    fun `reminder that expires during permission wait is skipped`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            val job =
                launch {
                    activity.attemptToScheduleNotifications(
                        listOf(notifications.single().copy(delay = 5_000L)),
                        factory,
                        false,
                        false,
                    )
                }
            runCurrent()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            job.join()
            verify(exactly = 0) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cancelled permission wait ignores late and duplicate results`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            val job = launch { activity.attemptToScheduleNotifications(notifications, factory) }
            runCurrent()
            val request = shadowOf(activity).lastRequestedPermission
            job.cancelAndJoin()
            repeat(2) {
                activity.onRequestPermissionsResult(
                    request.requestCode,
                    request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_GRANTED),
                )
            }
            verify(exactly = 0) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) }
        }

    @Test
    fun `destroyed activity releases the permission waiter`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val controller = Robolectric.buildActivity(SuperwallPaywallActivity::class.java)
            val activity = controller.get()
            activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            controller.create()
            val job = launch { activity.attemptToScheduleNotifications(notifications, factory) }
            runCurrent()
            controller.destroy()
            runCurrent()
            assertTrue(job.isCompleted)
            verify(exactly = 0) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) }
        }

    @Test
    fun `web reminders bypass native sandbox delay scaling`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            every { NotificationScheduler.scheduleNotifications(any(), any(), any(), any(), any()) } just Runs
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            activity.attemptToScheduleNotifications(notifications, factory, cancelExisting = false, applySandboxScaling = false)
            verify(exactly = 1) { NotificationScheduler.scheduleNotifications(notifications, factory, activity, false, false) }
        }

    @Test
    fun `granted notification permission schedules the reminders`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            activity.attemptToScheduleNotifications(notifications, factory)
            verify(exactly = 1) { NotificationScheduler.scheduleNotifications(notifications, factory, activity, false) }
        }

    @Test
    fun `denied permission completes the attempt without scheduling`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            val job = launch { activity.attemptToScheduleNotifications(notifications, factory) }
            runCurrent()
            assertFalse(job.isCompleted)
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
            runCurrent()
            assertTrue(job.isCompleted)
            verify(exactly = 0) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) }
        }

    @Test
    fun `scheduling failure after permission grant reaches the waiting caller`() =
        runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val activity = Robolectric.buildActivity(SuperwallPaywallActivity::class.java).get()
            val failure = IllegalStateException("WorkManager unavailable")
            every { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) } throws failure
            var received: Exception? = null
            val job =
                launch {
                    try {
                        activity.attemptToScheduleNotifications(notifications, factory)
                    } catch (e: Exception) {
                        received = e
                    }
                }
            runCurrent()
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            runCurrent()
            assertTrue(job.isCompleted)
            assertTrue(received is IllegalStateException)
            assertEquals(failure.message, received?.message)
        }
}
