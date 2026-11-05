package com.superwall.sdk.paywall.view

import android.app.Activity
import android.net.Uri
import com.google.android.play.core.review.ReviewInfo
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent
import com.superwall.sdk.review.ReviewManager
import com.superwall.sdk.storage.ReviewCount
import com.superwall.sdk.storage.ReviewData
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.transactions.TransactionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.ref.WeakReference
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallEventHandlerTest {
    private val dispatcher = StandardTestDispatcher()
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val delegate = mockk<SuperwallDelegateAdapter>(relaxed = true)
    private val reviewManager = mockk<ReviewManager>()
    private val deviceHelper = mockk<DeviceHelper>(relaxed = true)
    private val storage = mockk<Storage>(relaxed = true)
    private val notificationFactory = mockk<DeviceHelperFactory>()
    private val paywallInfo = mockk<PaywallInfo>(relaxed = true)
    private val paywallView =
        mockk<PaywallView>(relaxed = true) {
            every { info } returns paywallInfo
            every { encapsulatingActivity } returns null
        }

    private val tracked = mutableListOf<Trackable>()
    private val dismissed = mutableListOf<Triple<PaywallView, PaywallResult, PaywallCloseReason>>()
    private val opened = mutableListOf<Pair<Activity, String>>()
    private val scheduled = mutableListOf<Pair<PaywallWebEvent.ScheduleNotification, Activity?>>()
    private var rechecks = 0
    private var currentActivity: Activity? = null
    private var providedActivity: Activity? = null
    private var scheduleResult = true

    private fun TestScope.handler() =
        PaywallEventHandler(
            mainScope = MainScope(dispatcher),
            ioScope = IOScope(dispatcher),
            transactionManager = transactionManager,
            delegate = delegate,
            track = { tracked.add(it) },
            reviewManager = reviewManager,
            deviceHelper = deviceHelper,
            storage = storage,
            notificationFactory = notificationFactory,
            recheckPreload = { rechecks++ },
            currentActivity = { currentActivity },
            providedActivity = { providedActivity },
            dismissPaywall = { view, result, reason -> dismissed.add(Triple(view, result, reason)) },
            openUrl = { activity, url -> opened.add(activity to url) },
            scheduleNotifications = { _, event, activity, _ ->
                scheduled.add(event to activity)
                scheduleResult
            },
        )

    @Test
    fun `purchase shows the loading state only once the transaction manager admits it`() =
        runTest(dispatcher) {
            Given("a transaction manager that admits the purchase") {
                val admitted = slot<() -> Unit>()
                coEvery { transactionManager.purchase(any(), any(), capture(admitted)) } coAnswers {
                    PurchaseResult.Cancelled()
                }
                val handler = handler()

                When("the paywall sends InitiatePurchase") {
                    handler.eventDidOccur(PaywallWebEvent.InitiatePurchase("product", shouldDismiss = true), paywallView)
                    advanceUntilIdle()

                    Then("it purchases the product from the paywall and shows no loading state yet") {
                        coVerify(exactly = 1) {
                            transactionManager.purchase(
                                match { it is TransactionManager.PurchaseSource.Internal && it.productId == "product" },
                                true,
                                any(),
                            )
                        }
                        verify(exactly = 0) { paywallView.updateState(any()) }
                    }

                    And("the transaction manager admits it") {
                        admitted.captured()

                        Then("the paywall shows it is purchasing") {
                            verify(exactly = 1) {
                                paywallView.updateState(
                                    match {
                                        it is PaywallViewState.Updates.SetLoadingState &&
                                            it.newState == PaywallLoadingState.LoadingPurchase
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `close dismisses the paywall as a manual decline`() =
        runTest(dispatcher) {
            Given("a presented paywall") {
                val handler = handler()

                When("the paywall sends Closed") {
                    handler.eventDidOccur(PaywallWebEvent.Closed, paywallView)
                    advanceUntilIdle()

                    Then("it is dismissed with Declined and ManualClose") {
                        assertEquals(1, dismissed.size)
                        val (view, result, reason) = dismissed.single()
                        assertEquals(paywallView, view)
                        assert(result is PaywallResult.Declined)
                        assertEquals(PaywallCloseReason.ManualClose, reason)
                    }
                }
            }
        }

    @Test
    fun `restore goes through the transaction manager with the view`() =
        runTest(dispatcher) {
            Given("a presented paywall") {
                coEvery { transactionManager.tryToRestorePurchases(any(), any()) } returns RestorationResult.Restored()
                val handler = handler()

                When("the paywall sends InitiateRestore") {
                    handler.eventDidOccur(PaywallWebEvent.InitiateRestore, paywallView)
                    advanceUntilIdle()

                    Then("restore runs for that view") {
                        coVerify { transactionManager.tryToRestorePurchases(paywallView, any()) }
                    }
                }
            }
        }

    @Test
    fun `links and custom actions go to the delegate`() =
        runTest(dispatcher) {
            Given("a presented paywall") {
                val handler = handler()
                val url = URI("https://superwall.com")
                val deepLink = mockk<Uri>()

                When("the paywall opens URLs, a deep link and a custom action") {
                    handler.eventDidOccur(PaywallWebEvent.OpenedURL(url), paywallView)
                    handler.eventDidOccur(PaywallWebEvent.OpenedUrlInChrome(url), paywallView)
                    handler.eventDidOccur(PaywallWebEvent.OpenedDeepLink(deepLink), paywallView)
                    handler.eventDidOccur(PaywallWebEvent.Custom("action"), paywallView)
                    advanceUntilIdle()

                    Then("the delegate hears each one") {
                        verify(exactly = 2) { delegate.paywallWillOpenURL(url) }
                        verify { delegate.paywallWillOpenDeepLink(deepLink) }
                        verify { delegate.handleCustomPaywallAction("action") }
                    }
                }
            }
        }

    @Test
    fun `custom placement is tracked with its params and the paywall info`() =
        runTest(dispatcher) {
            Given("a paywall that sends a custom placement") {
                val handler = handler()
                val params = JsonObject(mapOf("plan" to JsonPrimitive("annual")))

                When("the placement arrives") {
                    handler.eventDidOccur(PaywallWebEvent.CustomPlacement("tapped", params), paywallView)
                    advanceUntilIdle()

                    Then("a CustomPlacement event is tracked") {
                        val event = tracked.single() as InternalSuperwallEvent.CustomPlacement
                        assertEquals("tapped", event.placementName)
                        assertEquals(mapOf("plan" to "annual"), event.params)
                        assertEquals(paywallInfo, event.paywallInfo)
                    }
                }
            }
        }

    @Test
    fun `in-app review increments the counter, tracks it and rechecks preload`() =
        runTest(dispatcher) {
            Given("an activity and two earlier review requests") {
                val activity = mockk<Activity>()
                currentActivity = activity
                val reviewInfo = mockk<ReviewInfo>()
                coEvery { reviewManager.requestReviewFlow() } returns reviewInfo
                coEvery { reviewManager.launchReviewFlow(activity, reviewInfo) } returns Unit
                coEvery { deviceHelper.reviewRequestsTotal() } returns 2
                val handler = handler()

                When("the paywall requests an in-app review") {
                    handler.eventDidOccur(
                        PaywallWebEvent.RequestReview(PaywallWebEvent.RequestReview.Type.INAPP),
                        paywallView,
                    )
                    advanceUntilIdle()

                    Then("the review flow launches and the count becomes 3") {
                        coVerify { reviewManager.launchReviewFlow(activity, reviewInfo) }
                        verify { storage.write(ReviewData, match<ReviewCount> { it.timesQueried == 3 }) }
                        val event = tracked.single() as InternalSuperwallEvent.ReviewRequested
                        assertEquals(3, event.count)
                        assertEquals(1, rechecks)
                    }
                }
            }
        }

    @Test
    fun `in-app review without an activity does nothing`() =
        runTest(dispatcher) {
            Given("no current activity") {
                coEvery { reviewManager.requestReviewFlow() } returns mockk()
                val handler = handler()

                When("the paywall requests an in-app review") {
                    handler.eventDidOccur(
                        PaywallWebEvent.RequestReview(PaywallWebEvent.RequestReview.Type.INAPP),
                        paywallView,
                    )
                    advanceUntilIdle()

                    Then("nothing is counted or tracked") {
                        verify(exactly = 0) { storage.write(ReviewData, any()) }
                        assertEquals(emptyList<Trackable>(), tracked)
                        assertEquals(0, rechecks)
                    }
                }
            }
        }

    @Test
    fun `external review opens the Play Store page`() =
        runTest(dispatcher) {
            Given("an app-supplied activity") {
                val activity = mockk<Activity>()
                providedActivity = activity
                every { deviceHelper.urlScheme } returns "com.example.app"
                val handler = handler()

                When("the paywall requests an external review") {
                    handler.eventDidOccur(
                        PaywallWebEvent.RequestReview(PaywallWebEvent.RequestReview.Type.EXTERNAL),
                        paywallView,
                    )
                    advanceUntilIdle()

                    Then("the Play Store listing opens from that activity") {
                        assertEquals(
                            listOf(activity to "https://play.google.com/store/apps/details?id=com.example.app"),
                            opened,
                        )
                    }
                }
            }
        }

    @Test
    fun `external review falls back to the paywall's activity`() =
        runTest(dispatcher) {
            Given("no app-supplied activity") {
                val hostActivity = mockk<Activity>()
                every { paywallView.encapsulatingActivity } returns WeakReference(hostActivity)
                every { deviceHelper.urlScheme } returns "com.example.app"
                val handler = handler()

                When("the paywall requests an external review") {
                    handler.eventDidOccur(
                        PaywallWebEvent.RequestReview(PaywallWebEvent.RequestReview.Type.EXTERNAL),
                        paywallView,
                    )
                    advanceUntilIdle()

                    Then("the listing opens from the activity hosting the paywall") {
                        assertEquals(hostActivity, opened.single().first)
                    }
                }
            }
        }

    @Test
    fun `review failure is caught`() =
        runTest(dispatcher) {
            Given("a review flow that fails") {
                coEvery { reviewManager.requestReviewFlow() } throws IllegalStateException("no play")
                val handler = handler()

                When("the paywall requests an in-app review") {
                    handler.eventDidOccur(
                        PaywallWebEvent.RequestReview(PaywallWebEvent.RequestReview.Type.INAPP),
                        paywallView,
                    )
                    advanceUntilIdle()

                    Then("nothing is tracked and nothing throws") {
                        assertEquals(emptyList<Trackable>(), tracked)
                    }
                }
            }
        }

    @Test
    fun `schedule notification is passed on with the current activity`() =
        runTest(dispatcher) {
            Given("a current activity") {
                val activity = mockk<Activity>()
                currentActivity = activity
                val handler = handler()
                val event =
                    PaywallWebEvent.ScheduleNotification(
                        LocalNotification(
                            type = LocalNotificationType.TrialStarted,
                            title = "Trial",
                            body = "Ends soon",
                            delay = 1000,
                        ),
                    )

                When("the paywall schedules a notification") {
                    scheduleResult = false
                    handler.eventDidOccur(event, paywallView)
                    advanceUntilIdle()

                    Then("the scheduler gets the event and the activity") {
                        assertEquals(listOf(event to activity), scheduled)
                    }
                }
            }
        }
}
