package com.superwall.sdk.paywall.view

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.view.LoadingOverlayState.Actions
import com.superwall.sdk.paywall.view.LoadingOverlayState.PendingHide
import com.superwall.sdk.paywall.view.LoadingOverlayState.Updates
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallLoadingOverlayTest {
    private val paywall =
        MutableStateFlow(
            PaywallViewState(
                paywall = Paywall.stub().apply { presentation = presentation.copy(delay = 500) },
                locale = "en-US",
            ),
        )
    private val tracked = mutableListOf<TrackableSuperwallEvent>()
    private val shimmer = mockk<PaywallShimmerView>(relaxed = true)
    private val spinner = mockk<PaywallPurchaseLoadingView>(relaxed = true)
    private var backgroundView: PaywallOptions.TransactionBackgroundView? =
        PaywallOptions.TransactionBackgroundView.SPINNER

    private fun TestScope.overlay(): PaywallLoadingOverlay {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return PaywallLoadingOverlay(
            scope = scope,
            delayScope = scope,
            storage = mockk(relaxed = true),
            tracker = { tracked += it },
            paywallState = { paywall.value },
            transactionBackgroundView = { backgroundView },
            preloadingEnabled = { true },
            applyPaywallUpdate = { update -> paywall.update(update.transform) },
            views = { PaywallLoadingOverlay.OverlayViews(shimmer, spinner) },
        )
    }

    private fun shimmerEvents(state: Class<*>) =
        tracked.filterIsInstance<InternalSuperwallEvent.ShimmerLoad>().filter { state.isInstance(it.state) }

    @Test
    fun `each loading state maps to its overlay change`() {
        Given("each move between loading states") {
        Then("spinner for purchases, shimmer for the URL, a delayed first reveal, an immediate later one") {
            assertEquals(OverlayChange.None, OverlayChange.of(PaywallLoadingState.Ready, PaywallLoadingState.Unknown))
            assertEquals(OverlayChange.ShowSpinner, OverlayChange.of(PaywallLoadingState.Ready, PaywallLoadingState.LoadingPurchase))
            assertEquals(OverlayChange.ShowSpinner, OverlayChange.of(PaywallLoadingState.Ready, PaywallLoadingState.ManualLoading))
            assertEquals(OverlayChange.ShowShimmer, OverlayChange.of(PaywallLoadingState.Unknown, PaywallLoadingState.LoadingURL))
            assertEquals(OverlayChange.RevealAfterDelay, OverlayChange.of(PaywallLoadingState.LoadingURL, PaywallLoadingState.Ready))
            assertEquals(OverlayChange.RevealAfterDelay, OverlayChange.of(PaywallLoadingState.Unknown, PaywallLoadingState.Ready))
            assertEquals(OverlayChange.Reveal, OverlayChange.of(PaywallLoadingState.LoadingPurchase, PaywallLoadingState.Ready))
        }
        }
    }

    @Test
    fun `the reducers record the handled state and the pending hide`() {
        val job = Job()
        val waiting = Updates.HideAfterDelay(job).reduce(LoadingOverlayState())
        assertEquals(PendingHide.Delayed(job), waiting.pendingHide)

        val handled = Updates.Handled(PaywallLoadingState.LoadingPurchase).reduce(waiting)
        assertEquals(PaywallLoadingState.LoadingPurchase, handled.lastHandled)
        assertEquals(PendingHide.None, handled.pendingHide)
        assertEquals(PendingHide.None, Updates.HideDone.reduce(waiting).pendingHide)
    }

    @Test
    fun `a paywall that isn't presented ignores loading states`() =
        runTest {
            val overlay = overlay()
            overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.LoadingPurchase, isPresented = false))

            verify(exactly = 0) { spinner.showLoading() }
            assertEquals(PaywallLoadingState.Unknown, overlay.state.value.lastHandled)
        }

    @Test
    fun `a purchase shows the spinner unless the app turned it off`() =
        runTest {
            Given("a presented paywall") {
                val overlay = overlay()

                When("a purchase starts with the spinner option, then again with no background view") {
                    overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.LoadingPurchase, isPresented = true))
                    backgroundView = null
                    overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.ManualLoading, isPresented = true))

                    Then("only the first shows the spinner") {
                        verify(exactly = 1) { spinner.showLoading() }
                    }
                }
            }
        }

    @Test
    fun `the first reveal waits for the presentation delay`() =
        runTest {
            Given("a presented paywall showing the shimmer") {
                val overlay = overlay()
                paywall.update(PaywallViewState.Updates.ShimmerStarted.transform)
                overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.LoadingURL, isPresented = true))
                verify { shimmer.showShimmer() }

                When("the paywall becomes ready") {
                    overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.Ready, isPresented = true))

                    Then("nothing hides before the delay") {
                        assertTrue(overlay.state.value.pendingHide is PendingHide.Delayed)
                        verify(exactly = 0) { shimmer.hideShimmer() }
                    }

                    advanceTimeBy(501)

                    Then("after it the shimmer and spinner hide and the shimmer end is tracked once") {
                        verify(exactly = 1) { shimmer.hideShimmer() }
                        verify(exactly = 1) { spinner.hideLoading() }
                        assertEquals(PendingHide.None, overlay.state.value.pendingHide)
                        assertNotNull(paywall.value.paywall.shimmerLoadingInfo.endAt)
                        assertEquals(1, shimmerEvents(InternalSuperwallEvent.ShimmerLoad.State.Complete::class.java).size)
                    }
                }
            }
        }

    @Test
    fun `a new loading state cancels a reveal that is still waiting`() =
        runTest {
            val overlay = overlay()
            overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.Ready, isPresented = true))
            overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.LoadingPurchase, isPresented = true))
            advanceTimeBy(1_000)

            verify(exactly = 0) { shimmer.hideShimmer() }
            verify(exactly = 1) { spinner.showLoading() }
        }

    @Test
    fun `a purchase that ends hides the spinner at once and resets crash retries`() =
        runTest {
            val overlay = overlay()
            overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.LoadingPurchase, isPresented = true))
            paywall.update(PaywallViewState.Updates.CrashRetry.transform)
            overlay.dispatch(Actions.LoadingStateChanged(PaywallLoadingState.Ready, isPresented = true))

            verify(exactly = 1) { spinner.hideLoading() }
            assertEquals(PendingHide.None, overlay.state.value.pendingHide)
            assertEquals(0, paywall.value.crashRetries)
        }

    @Test
    fun `the shimmer end is tracked only once`() =
        runTest {
            val overlay = overlay()
            overlay.dispatch(Actions.EndShimmer)
            overlay.dispatch(Actions.EndShimmer)

            assertEquals(1, shimmerEvents(InternalSuperwallEvent.ShimmerLoad.State.Complete::class.java).size)
        }

    @Test
    fun `the shimmer start is tracked with the paywall's delay`() =
        runTest {
            val overlay = overlay()
            overlay.dispatch(Actions.TrackShimmerStart)

            val started = shimmerEvents(InternalSuperwallEvent.ShimmerLoad.State.Started::class.java).single()
            assertEquals(500.0, started.delay, 0.0)
            assertNull(started.visibleDuration)
        }

    @Test
    fun `the visible duration needs both a shimmer start and an open`() {
        val open = Date(1_000)
        val now = Date(3_000)
        assertEquals(0.0, shimmerVisibleDuration(null, open, now), 0.0)
        assertEquals(0.0, shimmerVisibleDuration(Date(500), null, now), 0.0)
        assertEquals(2_000.0, shimmerVisibleDuration(Date(500), open, now), 0.0)
    }
}
