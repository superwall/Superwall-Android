package com.superwall.sdk.paywall.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SuperwallPaywallActivityNotificationPermissionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: ActivityController<SuperwallPaywallActivity>
    private lateinit var activity: SuperwallPaywallActivity
    private val factory = mockk<DeviceHelperFactory>(relaxed = true)
    private val notification =
        LocalNotification(
            type = LocalNotificationType.TrialStarted,
            title = "title",
            body = "body",
            delay = 1000,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(NotificationScheduler)
        every { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) } just Runs
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildActivity(SuperwallPaywallActivity::class.java)
        activity = controller.get()
    }

    @After
    fun tearDown() {
        unmockkObject(NotificationScheduler)
        Dispatchers.resetMain()
    }

    private fun deliverResult(granted: Boolean) =
        activity.onRequestPermissionsResult(
            SuperwallPaywallActivity.REQUEST_CODE_NOTIFICATION_PERMISSION,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )

    @Test
    fun `duplicate permission results do not crash and schedule only once`() =
        runTest(dispatcher) {
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    activity.attemptToScheduleNotifications(listOf(notification), factory)
                }
            advanceUntilIdle()

            deliverResult(granted = true)
            deliverResult(granted = true)
            advanceUntilIdle()

            assertTrue(job.isCompleted)
            verify(exactly = 1) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) }
        }

    @Test
    fun `overlapping requests share one permission request and both schedule on grant`() =
        runTest(dispatcher) {
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    activity.attemptToScheduleNotifications(listOf(notification), factory)
                }
            val second =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    activity.attemptToScheduleNotifications(listOf(notification), factory, cancelExisting = true)
                }
            advanceUntilIdle()
            assertTrue(!first.isCompleted && !second.isCompleted)

            deliverResult(granted = true)
            deliverResult(granted = true)
            advanceUntilIdle()

            assertTrue(first.isCompleted && second.isCompleted)
            verify(exactly = 1) { NotificationScheduler.scheduleNotifications(any(), any(), any(), cancelExisting = false) }
            verify(exactly = 1) { NotificationScheduler.scheduleNotifications(any(), any(), any(), cancelExisting = true) }
        }

    @Test
    fun `destroying the activity releases a pending waiter without scheduling`() =
        runTest(dispatcher) {
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    activity.attemptToScheduleNotifications(listOf(notification), factory)
                }
            advanceUntilIdle()

            // Never created, so super.onDestroy() throws in FragmentManager after our code has run.
            runCatching { controller.destroy() }
            advanceUntilIdle()

            assertTrue(job.isCompleted)
            verify(exactly = 0) { NotificationScheduler.scheduleNotifications(any(), any(), any(), any()) }
        }
}
