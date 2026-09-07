package com.superwall.sdk.web

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.ErrorInfo
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.models.internal.RedemptionResult.PaywallInfo.PaywallProduct
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.LocalNotificationType
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

internal fun trialRedemptionFixture(): WebRedemptionResponse =
    Json { ignoreUnknownKeys = true }.decodeFromString(
        requireNotNull(WebRedemptionTrialTest::class.java.getResource("/web-redemption-trial.json")).readText(),
    )

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebRedemptionTrialTest {
    private var response = trialRedemptionFixture()
    private val factory = mockk<WebPaywallRedeemer.Factory>(relaxed = true)
    private val network = mockk<Network>()
    private val storage = mockk<Storage>(relaxed = true)
    private val events = mutableListOf<Trackable>()
    private val order = mutableListOf<String>()
    private val reminder =
        LocalNotification("reminder", LocalNotificationType.TrialStarted, "Trial ending", body = "Reminder", delay = 86_400_000L)
    private var paywallInfo =
        PaywallInfo.empty().copy(
            identifier = "active_paywall",
            isFreeTrialAvailable = true,
            localNotifications = listOf(reminder),
        )
    private var visible = true
    private val result get() = response.codes.single() as RedemptionResult.Success

    init {
        every { factory.isWebToAppEnabled() } returns false
        every { factory.getUserId() } returns UserId("appUserId")
        every { factory.getDeviceId() } returns DeviceVendorId(VendorId("test-device"))
        every { factory.getAliasId() } returns null
        every { factory.getActiveDeviceEntitlements() } returns emptySet()
        every { factory.currentPaywallEntitlements() } answers { response.customerInfo!!.entitlements.toSet() }
        every { factory.maxAge() } returns 60_000L
        every { factory.getIntegrationProps() } returns emptyMap()
        every { factory.getExternalAccountId() } returns ""
        coEvery { factory.receipts() } returns emptyList()
        coEvery { factory.isPaywallVisible() } answers { visible }
        every { factory.isPaymentSheetOpen() } returns false
        every { factory.getPaywallInfo() } answers { paywallInfo }
        every { factory.internallySetSubscriptionStatus(any()) } answers { order += "access" }
        coEvery { factory.track(any()) } coAnswers {
            events += firstArg<Trackable>()
            if (firstArg<Trackable>() is InternalSuperwallEvent.FreeTrialStart) order += "trial"
        }
        coEvery { factory.scheduleTrialNotifications(any()) } coAnswers { order += "schedule" }
        coEvery { factory.triggerRestoreInPaywall() } coAnswers { order += "restore" }
        every { factory.closePaywallIfExists() } answers { order += "close" }
        every { factory.didRedeemLink(any()) } answers { order += "callback" }
        every { storage.read(LatestRedemptionResponse) } returns null
        coEvery { network.redeemToken(any(), any(), any(), any(), any(), any(), any()) } coAnswers { Either.Success(response) }
        coEvery { network.webEntitlementsByUserId(any(), any()) } coAnswers { awaitCancellation() }
    }

    private suspend fun TestScope.redeem(type: WebPaywallRedeemer.RedeemType = WebPaywallRedeemer.RedeemType.Code("TESTCODE")) {
        val scope = IOScope(StandardTestDispatcher(testScheduler))
        try {
            WebPaywallRedeemer(mockk(), scope, mockk(), network, storage, mockk(relaxed = true), factory).redeem(type)
        } finally {
            scope.cancel()
        }
    }

    private fun changeProduct(transform: (PaywallProduct) -> PaywallProduct?) {
        val info = result.redemptionInfo
        response =
            response.copy(
                codes =
                    listOf(
                        result.copy(
                            redemptionInfo =
                                info.copy(
                                    paywallInfo = info.paywallInfo!!.copy(product = transform(info.paywallInfo.product!!)),
                                ),
                        ),
                    ),
            )
    }

    private fun assertNoTrialSideEffects() {
        assertTrue(events.none { it is InternalSuperwallEvent.FreeTrialStart })
        coVerify(exactly = 0) { factory.scheduleTrialNotifications(any()) }
    }

    @Test
    fun `eligible redemption exposes product and tracks original trial data before either dismissal`() =
        runTest {
            redeem()
            assertEquals(listOf("access", "trial", "schedule", "restore", "close", "callback"), order)
            verify(exactly = 1) { factory.didRedeemLink(result) }
            verify { factory.internallySetSubscriptionStatus(SubscriptionStatus.Active(response.customerInfo!!.entitlements.toSet())) }
            coVerify(exactly = 1) {
                factory.scheduleTrialNotifications(listOf(reminder.copy(id = "active_paywall_TRIAL_STARTED")))
            }
            val event = events.filterIsInstance<InternalSuperwallEvent.FreeTrialStart>().single()
            assertEquals("freeTrial_start", event.rawName)
            assertEquals("test_product", event.product.fullIdentifier)
            assertEquals(7, event.product.trialPeriodDays)
            assertEquals("2026-09-14T12:30:00.000Z", event.product.trialPeriodEndDateString)
            val params = event.getSuperwallParameters()
            assertEquals("test_product", params["product_id"])
            assertEquals("7", params["product_trial_period_days"])
            assertEquals("$0.00", params["product_trial_period_price"])
        }

    @Test
    fun `legacy response without product still unlocks and calls delegate`() =
        runTest {
            changeProduct { null }
            redeem()
            assertNoTrialSideEffects()
            verify { factory.didRedeemLink(result) }
            verify { factory.internallySetSubscriptionStatus(SubscriptionStatus.Active(response.customerInfo!!.entitlements.toSet())) }
        }

    @Test
    fun `zero trial days skip trial side effects`() =
        runTest {
            changeProduct { it.copy(trialPeriodDays = 0) }
            redeem()
            assertNoTrialSideEffects()
        }

    @Test
    fun `ineligible paywall skips trial side effects`() =
        runTest {
            paywallInfo = paywallInfo.copy(isFreeTrialAvailable = false)
            redeem()
            assertNoTrialSideEffects()
        }

    @Test
    fun `no active paywall still delivers the full product`() =
        runTest {
            visible = false
            redeem()
            assertNoTrialSideEffects()
            verify { factory.didRedeemLink(result) }
            assertEquals(
                7,
                result.redemptionInfo.paywallInfo!!
                    .product!!
                    .trialPeriodDays,
            )
        }

    @Test
    fun `no trial reminders still tracks trial start`() =
        runTest {
            paywallInfo = paywallInfo.copy(localNotifications = listOf(reminder.copy(type = LocalNotificationType.Unsupported)))
            redeem()
            assertEquals(1, events.filterIsInstance<InternalSuperwallEvent.FreeTrialStart>().size)
            coVerify(exactly = 0) { factory.scheduleTrialNotifications(any()) }
        }

    @Test
    fun `background redemption refreshes never repeat trial side effects`() =
        runTest {
            redeem(WebPaywallRedeemer.RedeemType.Existing)
            redeem(WebPaywallRedeemer.RedeemType.IntegrationAttributes)
            assertNoTrialSideEffects()
            verify(exactly = 0) { factory.didRedeemLink(any()) }
        }

    @Test
    fun `failed code skips side effects even if another code has a trial`() =
        runTest {
            response = response.copy(codes = listOf(result.copy(code = "OTHER"), RedemptionResult.Error("TESTCODE", ErrorInfo("failed"))))
            redeem()
            assertNoTrialSideEffects()
            verify { factory.didRedeemLink(response.codes.last()) }
        }

    @Test
    fun `missing requested code returns error without using another products trial`() =
        runTest {
            response = response.copy(codes = listOf(result.copy(code = "OTHER")))
            redeem()
            assertNoTrialSideEffects()
            verify { factory.didRedeemLink(match { it is RedemptionResult.Error && it.code == "TESTCODE" }) }
        }

    @Test
    fun `scheduling failure preserves trial event access and callback`() =
        runTest {
            coEvery { factory.scheduleTrialNotifications(any()) } throws IllegalStateException("scheduler unavailable")
            redeem()
            assertEquals(1, events.filterIsInstance<InternalSuperwallEvent.FreeTrialStart>().size)
            verify { factory.didRedeemLink(result) }
            assertTrue(order.indexOf("access") < order.indexOf("callback"))
        }

    @Test
    fun `tracking failure still schedules reminders and delivers callback`() =
        runTest {
            coEvery { factory.track(match { it is InternalSuperwallEvent.FreeTrialStart }) } throws
                IllegalStateException("tracking unavailable")
            redeem()
            coVerify(exactly = 1) { factory.scheduleTrialNotifications(any()) }
            verify { factory.didRedeemLink(result) }
        }

    @Test
    fun `permission wait grants access immediately and defers dismissal until resolved`() =
        runTest {
            val permissionResult = CompletableDeferred<Unit>()
            coEvery { factory.scheduleTrialNotifications(any()) } coAnswers { permissionResult.await() }
            val job = launch { redeem() }
            runCurrent()
            verify { factory.internallySetSubscriptionStatus(SubscriptionStatus.Active(response.customerInfo!!.entitlements.toSet())) }
            coVerify(exactly = 0) { factory.triggerRestoreInPaywall() }
            verify(exactly = 0) { factory.closePaywallIfExists() }
            permissionResult.complete(Unit)
            job.join()
            verify(exactly = 1) { factory.didRedeemLink(result) }
            verify(exactly = 1) { factory.closePaywallIfExists() }
        }
}
