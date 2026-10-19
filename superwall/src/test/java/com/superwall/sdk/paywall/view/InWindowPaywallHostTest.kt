package com.superwall.sdk.paywall.view

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.paywall.PaywallURL
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.webview.PaywallUIDelegate
import com.superwall.sdk.paywall.view.webview.PaywallWebUI
import com.superwall.sdk.paywall.view.webview.SendPaywallMessages
import com.superwall.sdk.paywall.view.webview.WebviewError
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessage
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InWindowPaywallHostTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var paywallView: PaywallView
    private lateinit var messageHandler: PaywallMessageHandler
    private lateinit var delegate: SuperwallDelegateAdapter
    private lateinit var options: SuperwallOptions

    @Before
    fun setUp() {
        // A plain android.app.Activity, like UnityPlayerActivity: no lifecycle owner, no AppCompat.
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()

        options = SuperwallOptions().apply { paywalls.presentationHost = PaywallOptions.PresentationHost.IN_WINDOW }
        messageHandler = mockk(relaxed = true)
        delegate = mockk(relaxed = true)
        val factory = mockk<PaywallView.Factory>(relaxed = true)
        every { factory.makeSuperwallOptions() } returns options
        every { factory.delegate() } returns delegate
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
                webView = FakeWebUI(messageHandler),
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

    private val trialReminders =
        listOf(LocalNotification("trial", LocalNotificationType.TrialStarted, "Trial ending", body = "Reminder", delay = 86_400_000L))
    private val deviceHelperFactory = mockk<DeviceHelperFactory>()

    private val content: ViewGroup
        get() = activity.findViewById(android.R.id.content)

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun show(): InWindowPaywallHost = InWindowPaywallHost.show(activity, paywallView, null).getOrThrow().also { idleMain() }

    private fun show(style: PaywallPresentationStyle?): InWindowPaywallHost =
        InWindowPaywallHost.show(activity, paywallView, style).getOrThrow().also { idleMain() }

    /** Lets exit animations and their timeout fallback run. */
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun sheetBehavior(): BottomSheetBehavior<FrameLayout> = BottomSheetBehavior.from(paywallView.parent as FrameLayout)

    private fun waitUntil(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun notifications_ofAnInWindowPaywallAreScheduledThroughItsHost() =
        runTest {
            Given("an in-window paywall and a granted notification permission") {
                shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                val host = show()

                When("the paywall's notifications are looked up and scheduled") {
                    val notifications = PaywallNotifications.forPaywall(paywallView)
                    notifications?.attemptToSchedule(
                        trialReminders,
                        deviceHelperFactory,
                        cancelExisting = false,
                        applySandboxScaling = true,
                    )

                    Then("they come from the in-window host and reach the scheduler instead of being skipped") {
                        assertSame(host.notifications, notifications)
                        verify(exactly = 1) {
                            NotificationScheduler.scheduleNotifications(trialReminders, deviceHelperFactory, activity, false, true)
                        }
                    }
                }
            }
        }

    @Test
    fun notificationPermission_onAPlainActivityHostIsAskedForByAHeadlessActivity() =
        runTest {
            Given("an in-window paywall on a plain Activity without the notification permission") {
                shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                val host = show()

                When("its notifications are scheduled") {
                    val job =
                        launch {
                            host.notifications.attemptToSchedule(
                                trialReminders,
                                deviceHelperFactory,
                                cancelExisting = false,
                                applySandboxScaling = true,
                            )
                        }
                    runCurrent()

                    Then("the permission is asked for by a PermissionRequestActivity, not the host") {
                        assertFalse(job.isCompleted)
                        assertNull(shadowOf(activity).lastRequestedPermission)
                    }

                    When("the user grants it there") {
                        val intent = shadowOf(activity).nextStartedActivity
                        assertEquals(PermissionRequestActivity::class.java.name, intent.component?.className)
                        val requestActivity = Robolectric.buildActivity(PermissionRequestActivity::class.java, intent).setup().get()
                        val request = shadowOf(requestActivity).lastRequestedPermission
                        requestActivity.onRequestPermissionsResult(
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
    fun checkout_onAPlainActivityHostOpensTheSharedSheetForResult() {
        Given("a drawer paywall shown in-window on a plain Activity") {
            show(PaywallPresentationStyle.Drawer(height = 70.0, cornerRadius = 15.0))

            When("web checkout is presented") {
                paywallView.presentPaymentSheet("https://checkout.example.com/session")

                Then("the same partial-height Custom Tab as the activity path is launched for result") {
                    val started = shadowOf(activity).nextStartedActivityForResult
                    assertTrue(started.requestCode >= 0)
                    val expected = paywallView.checkoutIntent(activity, "https://checkout.example.com/session")
                    assertEquals("https://checkout.example.com/session", started.intent.dataString)
                    assertTrue(started.intent.hasExtra(CustomTabsIntent.EXTRA_INITIAL_ACTIVITY_HEIGHT_PX))
                    assertEquals(expected.extras!!.keySet(), started.intent.extras!!.keySet())
                    assertTrue(paywallView.isPaymentSheetPresented)
                }

                When("the host resumes after the sheet is closed") {
                    controller.pause()
                    controller.resume()
                    idleMain()

                    Then("the sheet counts as closed") {
                        assertFalse(paywallView.isPaymentSheetPresented)
                        assertFalse(paywallView.state.isBrowserViewPresented)
                    }
                }
            }
        }
    }

    @Test
    fun drawer_opensAsAHalfExpandedBottomSheetAtItsHeight() {
        Given("a paywall with a 70% drawer style") {
            When("it is shown in-window") {
                show(PaywallPresentationStyle.Drawer(height = 70.0, cornerRadius = 15.0))

                Then("it sits in a bottom sheet half expanded to 70% that can't collapse") {
                    val sheet = paywallView.parent as FrameLayout
                    assertTrue(sheet.parent is CoordinatorLayout)
                    val behavior = sheetBehavior()
                    assertEquals(0.7f, behavior.halfExpandedRatio, 0.001f)
                    assertTrue(behavior.skipCollapsed)
                    assertEquals(BottomSheetBehavior.STATE_HALF_EXPANDED, behavior.state)
                    assertTrue(sheet.clipToOutline)
                }
            }
        }
    }

    @Test
    fun modal_opensFullyExpandedBelowATopGap() {
        Given("a paywall with a modal style") {
            When("it is shown in-window") {
                show(PaywallPresentationStyle.Modal)

                Then("its sheet is fully expanded and starts 5% below the top of the screen") {
                    assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheetBehavior().state)
                    val coordinator = (paywallView.parent as View).parent as CoordinatorLayout
                    val expectedGap = (activity.resources.displayMetrics.heightPixels * 0.05).toInt()
                    assertEquals(expectedGap, (coordinator.layoutParams as FrameLayout.LayoutParams).topMargin)
                }
            }
        }
    }

    @Test
    fun popup_isSizedAsAPercentageOfTheWindow() {
        Given("a paywall with an 80% wide, 60% tall popup style") {
            When("it is shown in-window") {
                show(PaywallPresentationStyle.Popup(height = 60.0, width = 80.0, cornerRadius = 12.0))

                Then("it sits in a rounded card constrained to those percentages") {
                    val card = paywallView.parent as FrameLayout
                    assertTrue(card.parent is ConstraintLayout)
                    val params = card.layoutParams as ConstraintLayout.LayoutParams
                    assertEquals(0.8f, params.matchConstraintPercentWidth, 0.001f)
                    assertEquals(0.6f, params.matchConstraintPercentHeight, 0.001f)
                    assertTrue(card.clipToOutline)
                }
            }
        }
    }

    @Test
    fun close_animatesAFullscreenPaywallOutAndTearsItDownOnce() {
        Given("a full-screen paywall shown in-window") {
            val host = show(PaywallPresentationStyle.Fullscreen)
            val existingChildren = content.childCount

            When("it is closed") {
                host.close()
                host.close()

                Then("it is torn down exactly once after animating out, and the host keeps running") {
                    assertTrue(
                        waitUntil {
                            idleFor(700)
                            content.childCount == existingChildren - 1
                        },
                    )
                    verify(exactly = 1) { delegate.didDismissPaywall(any()) }
                    assertFalse(activity.isFinishing)
                }
            }
        }
    }

    @Test
    fun close_hidesADrawerAndTearsItDownOnce() {
        Given("a drawer shown in-window") {
            val host = show(PaywallPresentationStyle.Drawer(height = 70.0, cornerRadius = 15.0))
            val existingChildren = content.childCount

            When("it is closed") {
                host.close()

                Then("the sheet is hidden, the paywall torn down once, and the host keeps running") {
                    assertTrue(
                        waitUntil {
                            idleFor(700)
                            content.childCount == existingChildren - 1
                        },
                    )
                    verify(exactly = 1) { delegate.didDismissPaywall(any()) }
                    assertFalse(activity.isFinishing)
                }
            }
        }
    }

    @Test
    fun tappingTheDimmedBackdrop_dismissesASheetAsAManualClose() {
        Given("a drawer shown in-window") {
            show(PaywallPresentationStyle.Drawer(height = 70.0, cornerRadius = 15.0))
            val coordinator = (paywallView.parent as View).parent as CoordinatorLayout

            When("the backdrop around the sheet is tapped") {
                coordinator.performClick()

                Then("the paywall is dismissed as declined by a manual close") {
                    assertTrue(
                        waitUntil {
                            idleFor(700)
                            paywallView.state.paywallResult != null
                        },
                    )
                    assertTrue(paywallView.state.paywallResult is PaywallResult.Declined)
                    assertEquals(PaywallCloseReason.ManualClose, paywallView.state.paywall.closeReason)
                }
            }
        }
    }

    @Test
    fun show_attachesInADedicatedContainerAndCompletesPresentation() {
        Given("a paywall waiting for its presentation to complete") {
            var presented: Boolean? = null
            paywallView.updateState(PaywallViewState.Updates.SetPresentationConfig(null) { presented = it })
            val existingChildren = content.childCount

            When("it is shown in-window") {
                val host = show()

                Then("it sits alone in a new container on top of the host's content and is presented") {
                    assertEquals(existingChildren + 1, content.childCount)
                    val container = paywallView.parent as ViewGroup
                    assertSame(content, container.parent)
                    assertEquals(1, container.childCount)
                    assertSame(host, paywallView.inWindowHost)
                    assertSame(activity, paywallView.encapsulatingActivity?.get())
                    assertTrue(paywallView.state.isPresented)
                    assertEquals(true, presented)
                    verify(exactly = 1) { delegate.didPresentPaywall(any()) }
                }
            }
        }
    }

    @Test
    fun close_isIdempotent() {
        Given("a paywall shown in-window") {
            val host = show()
            var completions = 0
            paywallView.updateState(
                PaywallViewState.Updates.InitiateDismiss(PaywallResult.Declined(), PaywallCloseReason.SystemLogic) { completions++ },
            )

            When("it is closed twice") {
                host.close()
                host.close()
                idleMain()
                host.close()
                idleMain()

                Then("the presentation is torn down exactly once and the container is gone") {
                    verify(exactly = 1) { delegate.willDismissPaywall(any()) }
                    verify(exactly = 1) { delegate.didDismissPaywall(any()) }
                    assertEquals(1, completions)
                    assertFalse(paywallView.state.isPresented)
                    assertNull(paywallView.inWindowHost)
                    assertNull(paywallView.parent)
                    assertNull(InWindowPaywallHost.current)
                    assertEquals(0, content.childCount)
                }
            }
        }
    }

    @Test
    fun closingAStaleHost_doesNotAffectTheNewPresentation() {
        Given("a paywall closed and then presented again in-window") {
            val first = show()
            first.close()
            idleMain()
            val second = show()

            When("the first host is closed again") {
                first.close()
                idleMain()

                Then("the second presentation is untouched") {
                    assertTrue(first.token < second.token)
                    assertSame(second, paywallView.inWindowHost)
                    assertSame(second, InWindowPaywallHost.current)
                    assertTrue(paywallView.state.isPresented)
                    assertEquals(1, content.childCount)
                    verify(exactly = 1) { delegate.didDismissPaywall(any()) }
                }
            }
        }
    }

    @Test
    fun aHostSupersededBeforeItsTeardownRuns_leavesTheNewPresentationAlone() {
        Given("a paywall shown in-window") {
            val first = show()

            When("its close is still pending as the same view is shown by a newer host") {
                first.close()
                val second = show()

                Then("the old teardown doesn't dismiss the view or remove the new container") {
                    assertSame(second, paywallView.inWindowHost)
                    assertTrue(paywallView.state.isPresented)
                    assertEquals(1, content.childCount)
                    assertSame(content, (paywallView.parent as View).parent)
                    verify(exactly = 0) { delegate.didDismissPaywall(any()) }
                }
            }
        }
    }

    @Test
    fun dismiss_closesTheOverlayWithoutFinishingTheHostActivity() {
        Given("a paywall shown in-window") {
            show()

            When("the paywall is dismissed") {
                var completed = false
                paywallView.dismiss(PaywallResult.Declined(), PaywallCloseReason.SystemLogic) { completed = true }

                Then("the overlay is torn down and the host activity keeps running") {
                    assertTrue(waitUntil { completed && content.childCount == 0 })
                    assertFalse(activity.isFinishing)
                    assertNull(paywallView.inWindowHost)
                    assertNull(paywallView.encapsulatingActivity)
                    verify(exactly = 1) { delegate.didDismissPaywall(any()) }
                }
            }
        }
    }

    @Test
    fun activityMode_isTheDefaultAndStillFinishesTheEncapsulatingActivity() {
        Given("default paywall options and a paywall encapsulated by an activity") {
            assertEquals(PaywallOptions.PresentationHost.ACTIVITY, PaywallOptions().presentationHost)
            options.paywalls.presentationHost = PaywallOptions.PresentationHost.ACTIVITY
            paywallView.encapsulatingActivity = WeakReference(activity)

            When("the paywall is dismissed") {
                paywallView.dismiss(PaywallResult.Declined(), PaywallCloseReason.SystemLogic)

                Then("the encapsulating activity is finished, as before") {
                    assertTrue(waitUntil { activity.isFinishing })
                    assertNull(InWindowPaywallHost.current)
                }
            }
        }
    }

    @Test
    fun hostGoingToBackground_isANonFinishingStopAndResumeReopens() {
        Given("a paywall shown in-window") {
            show()

            When("the host activity is paused and stopped") {
                controller.pause().stop()
                idleMain()

                Then("the paywall is closed for background but not dismissed") {
                    assertTrue(paywallView.state.closedForBackground)
                    assertTrue(paywallView.state.isPresented)
                    verify(exactly = 0) { delegate.didDismissPaywall(any()) }
                }

                When("the host activity resumes") {
                    controller.restart().resume()
                    idleMain()

                    Then("the same presentation is reopened") {
                        assertFalse(paywallView.state.closedForBackground)
                        assertTrue(paywallView.state.isPresented)
                        assertEquals(1, content.childCount)
                    }
                }
            }
        }
    }

    @Test
    fun hostDestroyed_forcesTeardown() {
        Given("a paywall shown in-window") {
            show()

            When("the host activity is destroyed") {
                controller.pause().stop().destroy()
                idleMain()

                Then("the presentation is dismissed") {
                    verify(exactly = 1) { delegate.didDismissPaywall(any()) }
                    assertFalse(paywallView.state.isPresented)
                    assertNull(InWindowPaywallHost.current)
                }
            }
        }
    }

    @Test
    fun backKey_isForwardedToThePaywall() {
        Given("a paywall shown in-window") {
            show()

            When("the back key is pressed") {
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))

                Then("it goes to the paywall and not to the host activity") {
                    verify(exactly = 1) { messageHandler.handle(PaywallMessage.BackButtonPressed) }
                    assertFalse(activity.isFinishing)
                }
            }
        }
    }

    internal class FakeWebUI(
        override val messageHandler: PaywallMessageHandler,
    ) : PaywallWebUI {
        override var delegate: PaywallUIDelegate? = null
        override var onScrollChangeListener: PaywallWebUI.OnScrollChangeListener? = null

        override fun onView(perform: View.() -> Unit) = Unit

        override fun enableBackgroundRendering() = Unit

        override fun scrollBy(
            x: Int,
            y: Int,
        ) = Unit

        override fun scrollTo(
            x: Int,
            y: Int,
        ) = Unit

        override fun setup(
            url: PaywallURL,
            onRenderCrashed: (Boolean, Int) -> Unit,
            onLoadFailed: (WebviewError) -> Unit,
        ) = Unit

        override fun evaluate(
            code: String,
            resultCallback: ((String?) -> Unit)?,
        ) = Unit

        override fun destroyView() = Unit

        override fun detach(fromView: ViewGroup) = Unit

        override fun attach(toView: ViewGroup) = Unit
    }
}
