package com.superwall.sdk.paywall.view

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.view.PresentationLifecycleState.Actions
import com.superwall.sdk.paywall.view.PresentationLifecycleState.LoadTimeout
import com.superwall.sdk.paywall.view.PresentationLifecycleState.PendingWebviewFailure
import com.superwall.sdk.paywall.view.PresentationLifecycleState.Updates
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.WebviewError
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallPresentationLifecycleTest {
    private val paywall = MutableStateFlow(PaywallViewState(paywall = Paywall.stub(), locale = "en-US"))
    private val tracked = mutableListOf<TrackableSuperwallEvent>()
    private var dismissals = 0

    private fun TestScope.lifecycle(scope: CoroutineScope = backgroundScope) =
        PaywallPresentationLifecycle(
            scope = scope,
            loadWatchScope = scope,
            storage = mockk(relaxed = true),
            tracker = { tracked += it },
            paywallState = { paywall.value },
            paywallStates = paywall,
            applyPaywallUpdate = { update -> paywall.update(update.transform) },
            dismiss = { dismissals++ },
        )

    private fun TestScope.unconfined() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun apply(update: PaywallViewState.Updates) = paywall.update(update.transform)

    @Test
    fun `the failure outcome follows where the presentation is`() {
        Given("a paywall in each stage of a presentation") {
            val idle = PaywallViewState(paywall = Paywall.stub(), locale = "en-US")
            val starting = PaywallViewState.Updates.SetPresentationConfig(null) {}.transform(idle)
            val presented = PaywallViewState.Updates.SetPresentedAndFinished.transform(idle)

            Then("on screen dismisses, starting waits for the view, off screen reloads later") {
                assertEquals(WebviewFailureOutcome.Dismiss, WebviewFailureOutcome.of(presented))
                assertEquals(WebviewFailureOutcome.DismissOnceViewCreated, WebviewFailureOutcome.of(starting))
                assertEquals(WebviewFailureOutcome.ReloadWhenNextPresented, WebviewFailureOutcome.of(idle))
            }
        }
    }

    @Test
    fun `the reducers set and clear the watch and the deferred failure`() {
        val job = Job()
        val watching = Updates.WatchLoad(job).reduce(PresentationLifecycleState())
        val deferred = Updates.DeferFailureUntilViewCreated.reduce(watching)

        assertEquals(LoadTimeout.Watching(job), watching.loadTimeout)
        assertEquals(PendingWebviewFailure.DismissOnceViewCreated, deferred.pendingFailure)
        assertEquals(LoadTimeout.Off, Updates.StopWatchingLoad.reduce(deferred).loadTimeout)
        assertEquals(PendingWebviewFailure.None, Updates.ClearPendingFailure.reduce(deferred).pendingFailure)
    }

    @Test
    fun `a failure on screen dismisses the paywall and records the failure`() =
        runTest {
            Given("a presented paywall") {
                val lifecycle = lifecycle(unconfined())
                apply(PaywallViewState.Updates.SetPresentedAndFinished)

                When("the webview fails") {
                    lifecycle.dispatch(Actions.WebviewFailed)

                    Then("the paywall is dismissed once and marked as failed to load") {
                        assertEquals(1, dismissals)
                        assertTrue(paywall.value.webviewFailedToLoad)
                        assertEquals(PendingWebviewFailure.None, lifecycle.state.value.pendingFailure)
                    }
                }
            }
        }

    @Test
    fun `a failure while the activity starts waits for the view, then dismisses`() =
        runTest {
            Given("a presentation whose activity hasn't created the view") {
                val lifecycle = lifecycle(unconfined())
                apply(PaywallViewState.Updates.SetPresentationConfig(null) {})

                When("the webview fails") {
                    lifecycle.dispatch(Actions.WebviewFailed)

                    Then("nothing is dismissed yet and the failure waits") {
                        assertEquals(0, dismissals)
                        assertEquals(PendingWebviewFailure.DismissOnceViewCreated, lifecycle.state.value.pendingFailure)
                    }
                }

                When("the view is created") {
                    apply(PaywallViewState.Updates.ClearViewCreatedCompletion)
                    apply(PaywallViewState.Updates.SetPresentedAndFinished)
                    lifecycle.dispatch(Actions.ViewCreated)

                    Then("the paywall is dismissed and the wait is cleared") {
                        assertEquals(1, dismissals)
                        assertEquals(PendingWebviewFailure.None, lifecycle.state.value.pendingFailure)
                    }
                }
            }
        }

    @Test
    fun `a new presentation drops a failure deferred for the old one`() =
        runTest {
            Given("a failure waiting for the view") {
                val lifecycle = lifecycle(unconfined())
                apply(PaywallViewState.Updates.SetPresentationConfig(null) {})
                lifecycle.dispatch(Actions.WebviewFailed)

                When("a new presentation takes the view over, and its view is created") {
                    lifecycle.dispatch(Actions.ResetForNewPresentation)
                    lifecycle.dispatch(Actions.ViewCreated)

                    Then("nothing is dismissed") {
                        assertEquals(0, dismissals)
                    }
                }
            }
        }

    @Test
    fun `a failure off screen only records it`() =
        runTest {
            val lifecycle = lifecycle(unconfined())

            lifecycle.dispatch(Actions.WebviewFailed)

            assertEquals(0, dismissals)
            assertTrue(paywall.value.webviewFailedToLoad)
            assertEquals(PendingWebviewFailure.None, lifecycle.state.value.pendingFailure)
        }

    @Test
    fun `a paywall that misses timeoutAfter is tracked as a timeout and dismissed`() =
        runTest {
            Given("a presented paywall with a 5 second timeout") {
                val lifecycle = lifecycle()
                apply(PaywallViewState.Updates.SetPresentedAndFinished)
                lifecycle.dispatch(Actions.WatchLoad(5.seconds))
                advanceTimeBy(1_000)

                Then("the load is being watched") {
                    assertTrue(lifecycle.state.value.loadTimeout is LoadTimeout.Watching)
                }

                When("the paywall never becomes ready") {
                    advanceTimeBy(5_000)
                    advanceUntilIdle()

                    Then("a webview timeout is tracked and the paywall dismissed") {
                        val fail =
                            tracked
                                .filterIsInstance<InternalSuperwallEvent.PaywallWebviewLoad>()
                                .single()
                                .state as InternalSuperwallEvent.PaywallWebviewLoad.State.Fail
                        assertTrue(fail.error is WebviewError.Timeout)
                        assertEquals(1, dismissals)
                    }
                }
            }
        }

    @Test
    fun `a paywall ready in time is not failed`() =
        runTest {
            val lifecycle = lifecycle()
            apply(PaywallViewState.Updates.SetPresentedAndFinished)
            lifecycle.dispatch(Actions.WatchLoad(5.seconds))
            advanceTimeBy(1_000)

            apply(PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.Ready))
            advanceTimeBy(10_000)
            advanceUntilIdle()

            assertTrue(tracked.isEmpty())
            assertEquals(0, dismissals)
        }

    @Test
    fun `dismissing stops the watch`() =
        runTest {
            val lifecycle = lifecycle()
            apply(PaywallViewState.Updates.SetPresentedAndFinished)
            lifecycle.dispatch(Actions.WatchLoad(5.seconds))
            advanceTimeBy(1_000)

            lifecycle.dispatch(Actions.StopWatchingLoad)
            advanceTimeBy(10_000)
            advanceUntilIdle()

            assertEquals(LoadTimeout.Off, lifecycle.state.value.loadTimeout)
            assertTrue(tracked.isEmpty())
            assertEquals(0, dismissals)
        }

    @Test
    fun `no timeoutAfter means no watch`() =
        runTest {
            val lifecycle = lifecycle(unconfined())

            lifecycle.dispatch(Actions.WatchLoad(null))

            assertEquals(LoadTimeout.Off, lifecycle.state.value.loadTimeout)
        }
}
