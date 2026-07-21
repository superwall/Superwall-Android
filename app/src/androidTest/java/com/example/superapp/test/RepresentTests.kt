package com.example.superapp.test

import android.app.Application
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.example.superapp.utils.awaitUntilWebviewAppears
import com.example.superapp.utils.clickButtonWith
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.store.testmode.TestModeBehavior
import com.superwall.superapp.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Emulator (Firebase Test Lab) regression tests for PR #434
 * (`fix/paywall-transient-state-reset-on-represent`).
 *
 * Repro: after a consumable purchase, re-presenting the SAME paywall in one app
 * session left the buy button dead — a stale `LoadingPurchase` loading overlay (and
 * stale `presentationDidFinishPrepare`) carried onto the re-presented CACHED paywall
 * and swallowed the tap. The fix resets that transient state upstream in
 * `PaywallManager.getPaywallView`'s cache-hit branch, so BOTH the full-screen
 * `register()` path and the embedded `getPaywall`/`getPaywallView` path are covered.
 *
 * These tests drive the EMBEDDED path — the one the customer hit, and the one that was
 * previously unfixed because the embed API never calls `present()`. A fragment
 * ([PaywallHostFragment]) embeds the paywall via `PaywallBuilder`/`getPaywall`,
 * purchases via Superwall test mode (billing is unavailable on CI emulators), then
 * re-obtains/re-attaches the embedded paywall and re-taps buy.
 *
 * Assertions are event/state based (no golden screenshots) so they can be validated
 * on FTL without locally generated baseline images.
 */
@RunWith(AndroidJUnit4::class)
class RepresentTests {
    companion object {
        /**
         * Placement that must present a NON-gated paywall backed by a CONSUMABLE
         * product (one that grants NO persistent entitlement), so the same paywall
         * can be re-presented and re-bought within a single session.
         *
         * `non_recurring_product` is the closest existing placement (see
         * `UITestHandler.testAndroid20Info`, "Non-recurring product purchase"). If it
         * turns out to be gated / to grant a blocking entitlement, point this constant
         * at a dedicated consumable re-buy placement instead.
         */
        const val CONSUMABLE_REBUY_PLACEMENT = "non_recurring_product"

        /**
         * The paywall's primary purchase CTA label (WebView text). Must be DISTINCT
         * from the test-mode activation modal's "Continue" button, otherwise the buy
         * tap is ambiguous. Update to match the configured paywall's button text.
         */
        const val BUY_BUTTON_TEXT = "Continue"

        // Test-mode purchase drawer confirm button (set in TestModePurchaseDrawer).
        private const val CONFIRM_PURCHASE_TEXT = "Confirm Purchase"

        // Unique substring of the test-mode activation modal (dashboard hint row),
        // used to detect the modal without colliding with paywall/webview text.
        private const val TEST_MODE_MODAL_HINT = "Configure test mode users"

        private val EVENT_TIMEOUT = 30.seconds
        private val UI_TIMEOUT_MS = 30_000L
    }

    // Live event stream fed by the Superwall delegate installed in [setup].
    private val events = MutableSharedFlow<SuperwallEvent>(replay = 64, extraBufferCapacity = 256)

    @Before
    fun setup() {
        Superwall.configure(
            getInstrumentation().targetContext.applicationContext as Application,
            Keys.CONSTANT_API_KEY,
            options =
                SuperwallOptions().apply {
                    logging.level = LogLevel.debug
                    // Billing is unavailable on CI emulators — ALWAYS routes purchases
                    // through the in-app test-mode drawer instead of Google Play.
                    testModeBehavior = TestModeBehavior.ALWAYS
                    paywalls =
                        PaywallOptions().apply {
                            shouldPreload = false
                        }
                },
        )
        Superwall.instance.delegate =
            object : SuperwallDelegate {
                override fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
                    events.tryEmit(eventInfo.event)
                }
            }
    }

    /**
     * Test 1 — repro + fix, end-to-end (embedded path).
     *
     * Embed → buy → drawer → Confirm → complete → re-embed the SAME placement →
     * tap buy AGAIN. Pre-fix the second tap is dead (stale overlay swallows it) so the
     * drawer never reappears; post-fix the purchase flow is re-invoked because the
     * cache-hit reset in `PaywallManager` cleared the stale `LoadingPurchase` overlay.
     */
    @Test
    fun test_represent_after_consumable_purchase_reinvokes_purchase() =
        runBlocking {
            awaitConfigured()

            val scenario = launchFragmentInContainer<PaywallHostFragment>()

            // First embedded presentation.
            dismissTestModeActivationModalIfPresent()
            assertTrue("First paywall never appeared", awaitUntilWebviewAppears())
            delay(1.seconds)

            // First purchase.
            clickButtonWith(BUY_BUTTON_TEXT)
            assertTrue(
                "Purchase drawer did not appear on the FIRST buy tap",
                awaitTextAppears(CONFIRM_PURCHASE_TEXT),
            )
            val firstComplete = awaitEventAsync { it is SuperwallEvent.TransactionComplete }
            clickButtonWith(CONFIRM_PURCHASE_TEXT)
            assertNotNull("First purchase never completed", firstComplete.await())
            // The embedded paywall isn't dismissed (no full-screen activity to finish);
            // just wait for the test-mode drawer to close so the re-tap detects a FRESH drawer.
            awaitTextDisappears(CONFIRM_PURCHASE_TEXT)
            delay(1.seconds)

            // Re-obtain/re-attach the SAME placement via the embedded API (reuses the cached
            // PaywallView through PaywallManager.getPaywallView's cache-hit reset).
            scenario.onFragment { it.present() }
            dismissTestModeActivationModalIfPresent()
            assertTrue("Re-presented paywall never appeared", awaitUntilWebviewAppears())
            delay(1.seconds)

            // Tap buy AGAIN — dead pre-fix, alive post-fix (PR #434).
            clickButtonWith(BUY_BUTTON_TEXT)
            assertTrue(
                "Re-presented paywall's buy tap did NOT re-invoke the purchase flow — " +
                    "stale transient presentation state regression (PR #434)",
                awaitTextAppears(CONFIRM_PURCHASE_TEXT),
            )
        }

    /**
     * Test 2 — public-API state assertion (analog of the internal-reset unit test).
     *
     * After purchasing and re-embedding, the re-presented paywall must NOT be stuck in
     * `LoadingPurchase` (which is what showed the tap-swallowing overlay). `:app:` can't
     * see the internal reset API, so we assert via the public `loadingState` getter.
     */
    @Test
    fun test_represent_resets_loading_state() =
        runBlocking {
            awaitConfigured()

            val scenario = launchFragmentInContainer<PaywallHostFragment>()

            dismissTestModeActivationModalIfPresent()
            assertTrue("First paywall never appeared", awaitUntilWebviewAppears())
            delay(1.seconds)

            // Purchase once so a LoadingPurchase state exists to (previously) leak.
            clickButtonWith(BUY_BUTTON_TEXT)
            assertTrue(
                "Purchase drawer did not appear on the buy tap",
                awaitTextAppears(CONFIRM_PURCHASE_TEXT),
            )
            val complete = awaitEventAsync { it is SuperwallEvent.TransactionComplete }
            clickButtonWith(CONFIRM_PURCHASE_TEXT)
            assertNotNull("Purchase never completed", complete.await())
            awaitTextDisappears(CONFIRM_PURCHASE_TEXT)
            delay(1.seconds)

            // Re-obtain/re-attach the cached embedded paywall (exercises the cache-hit reset).
            scenario.onFragment { it.present() }
            dismissTestModeActivationModalIfPresent()
            assertTrue("Re-presented paywall never appeared", awaitUntilWebviewAppears())
            delay(1.seconds)

            val paywallView = Superwall.instance.paywallView
            assertNotNull("No paywallView after re-present", paywallView)
            val loadingState = paywallView!!.loadingState
            // Post-fix `resetTransientPresentationState()` puts LoadingPurchase back to Ready,
            // so the tap-swallowing overlay is not shown.
            assertTrue(
                "Re-presented paywall carried a stale LoadingPurchase state (PR #434); was $loadingState",
                loadingState !is PaywallLoadingState.LoadingPurchase,
            )
        }

    // region helpers

    private suspend fun awaitConfigured() {
        Superwall.instance.configurationStateListener.first { it is ConfigurationStatus.Configured }
        // Force a DEFINITE entitlement status. On CI emulators Google Play Billing is
        // unavailable (developer error 5), so the status otherwise stays `Unknown` and
        // paywall presentation is SKIPPED with `subscription_status_timeout` (the status
        // stayed "unknown" for >5s). `Inactive` means "no entitlements" so the non-gated
        // paywall still presents. Mirrors every paywall-presenting test in `UITestHandler`.
        Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)
    }

    private fun device() = UiDevice.getInstance(getInstrumentation())

    /**
     * The ALWAYS test-mode activation modal auto-presents once (per just-activated
     * session) and is non-cancelable. Detect it via its unique dashboard hint text and
     * tap "Continue" to get past it. Best-effort: absent modal is not an error.
     */
    private suspend fun dismissTestModeActivationModalIfPresent() {
        val d = device()
        val modal = d.wait(Until.findObject(By.textContains(TEST_MODE_MODAL_HINT)), 10_000)
        if (modal != null) {
            d.findObject(UiSelector().textContains("Continue")).click()
            d.waitForIdle()
            delay(500.milliseconds)
        }
    }

    private fun awaitTextAppears(
        text: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): Boolean = device().wait(Until.findObject(By.textContains(text)), timeoutMs) != null

    /**
     * Waits until [text] is no longer on screen (best-effort, bounded by [timeoutMs]).
     * Used after confirming a test-mode purchase so the subsequent buy re-tap detects a
     * FRESH drawer rather than the lingering previous one. The embedded paywall itself is
     * never dismissed (there's no full-screen activity to finish), so we can't wait on the
     * webview disappearing.
     */
    private fun awaitTextDisappears(
        text: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): Boolean = device().wait(Until.gone(By.textContains(text)), timeoutMs) ?: false

    /**
     * Subscribe to [events] BEFORE the triggering action (avoids the emit/collect race),
     * returning a Deferred that resolves to the matching event or null on timeout.
     */
    private fun CoroutineScope.awaitEventAsync(predicate: (SuperwallEvent) -> Boolean) =
        async(Dispatchers.IO) {
            withTimeoutOrNull(EVENT_TIMEOUT) { events.first(predicate) }
        }

    // endregion
}
