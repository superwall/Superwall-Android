package com.superwall.sdk.paywall.view

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.paywall.view.webview.SendPaywallMessages
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessage
import com.superwall.sdk.permissions.PermissionRequestActivity
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** In-window hosting on a ComponentActivity, like UnityPlayerGameActivity (an AppCompatActivity). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InWindowComponentActivityHostTest {
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private lateinit var paywallView: PaywallView

    private val checkoutUrl = "https://checkout.example.com/session"
    private val trialReminders =
        listOf(LocalNotification("trial", LocalNotificationType.TrialStarted, "Trial ending", body = "Reminder", delay = 86_400_000L))
    private val deviceHelperFactory = mockk<DeviceHelperFactory>()

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()

        val options = SuperwallOptions().apply { paywalls.presentationHost = PaywallOptions.PresentationHost.IN_WINDOW }
        val factory = mockk<PaywallView.Factory>(relaxed = true)
        every { factory.makeSuperwallOptions() } returns options
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
        InWindowPaywallHost.current?.close()
        idleMain()
        unmockkObject(NotificationScheduler.Companion)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun show(style: PaywallPresentationStyle? = null): InWindowPaywallHost =
        InWindowPaywallHost.show(activity, paywallView, style).getOrThrow().also { idleMain() }

    @Test
    fun checkout_isLaunchedThroughTheRegistryAndItsResultClosesTheSheet() {
        Given("a drawer paywall shown in-window on a ComponentActivity") {
            show(PaywallPresentationStyle.Drawer(height = 70.0, cornerRadius = 15.0))

            When("web checkout is presented") {
                paywallView.presentPaymentSheet(checkoutUrl)
                val started = shadowOf(activity).nextStartedActivityForResult

                Then("the shared partial-height Custom Tab is launched for result") {
                    val expected = paywallView.checkoutIntent(activity, checkoutUrl)
                    assertEquals(checkoutUrl, started.intent.dataString)
                    assertTrue(started.intent.hasExtra(CustomTabsIntent.EXTRA_INITIAL_ACTIVITY_HEIGHT_PX))
                    assertEquals(expected.extras!!.keySet(), started.intent.extras!!.keySet())
                    assertTrue(paywallView.isPaymentSheetPresented)
                }

                When("the host resumes before the result arrives") {
                    controller.pause()
                    controller.resume()
                    idleMain()

                    Then("the sheet still counts as open") {
                        assertTrue(paywallView.isPaymentSheetPresented)
                    }
                }

                When("the Custom Tab's result arrives") {
                    paywallView.updateState(PaywallViewState.Updates.SetBrowserPresented(true))
                    shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)

                    Then("it is handled like the activity path") {
                        assertFalse(paywallView.isPaymentSheetPresented)
                        assertFalse(paywallView.state.isBrowserViewPresented)
                    }
                }
            }
        }
    }

    @Test
    fun checkout_resultAfterTheHostClosedIsIgnored() {
        Given("web checkout opened from an in-window paywall on a ComponentActivity") {
            val host = show()
            paywallView.presentPaymentSheet(checkoutUrl)
            val started = shadowOf(activity).nextStartedActivityForResult

            When("the paywall closes before the Custom Tab returns") {
                host.close()
                idleMain()
                paywallView.updateState(PaywallViewState.Updates.SetBrowserPresented(true))
                shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)

                Then("the launcher was unregistered, so the late result changes nothing") {
                    assertTrue(paywallView.state.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun notificationPermission_isAskedForThroughTheRegistry() =
        runTest {
            Given("an in-window paywall on a ComponentActivity without the notification permission") {
                shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                show()

                When("its notifications are scheduled") {
                    val job =
                        launch {
                            PaywallNotifications
                                .forPaywall(paywallView)!!
                                .attemptToSchedule(trialReminders, deviceHelperFactory, cancelExisting = false, applySandboxScaling = true)
                        }
                    runCurrent()
                    val request = shadowOf(activity).lastRequestedPermission

                    Then("the host itself asks for the permission, without a headless activity") {
                        assertFalse(job.isCompleted)
                        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
                        val started = shadowOf(activity).nextStartedActivity
                        assertNotEquals(PermissionRequestActivity::class.java.name, started?.component?.className)
                    }

                    When("the user grants it") {
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
}
