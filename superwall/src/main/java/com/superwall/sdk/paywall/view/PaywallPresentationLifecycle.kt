package com.superwall.sdk.paywall.view

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.webview.WebviewError
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The parts of a paywall presentation that outlive a single call: the `paywalls.timeoutAfter`
 * watch and a webview failure waiting for the activity. Owned by [PaywallPresentationLifecycle].
 */
internal data class PresentationLifecycleState(
    val loadTimeout: LoadTimeout = LoadTimeout.Off,
    val pendingFailure: PendingWebviewFailure = PendingWebviewFailure.None,
) {
    /** Whether the presentation waits for the webview against `paywalls.timeoutAfter`. */
    sealed class LoadTimeout {
        object Off : LoadTimeout()

        /** [job] fails the presentation if the paywall isn't ready in time. */
        data class Watching(
            val job: Job,
        ) : LoadTimeout()
    }

    /** A webview failure that can't be handled until the activity has created the view. */
    sealed class PendingWebviewFailure {
        object None : PendingWebviewFailure()

        object DismissOnceViewCreated : PendingWebviewFailure()
    }

    // -----------------------------------------------------------------------
    // Pure state mutations — (PresentationLifecycleState) -> PresentationLifecycleState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (PresentationLifecycleState) -> PresentationLifecycleState,
    ) : Reducer<PresentationLifecycleState> {
        data class WatchLoad(
            val job: Job,
        ) : Updates({ it.copy(loadTimeout = LoadTimeout.Watching(job)) })

        object StopWatchingLoad : Updates({ it.copy(loadTimeout = LoadTimeout.Off) })

        object DeferFailureUntilViewCreated : Updates({
            it.copy(pendingFailure = PendingWebviewFailure.DismissOnceViewCreated)
        })

        object ClearPendingFailure : Updates({ it.copy(pendingFailure = PendingWebviewFailure.None) })
    }

    // -----------------------------------------------------------------------
    // Actions — async work via PresentationLifecycleContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend PresentationLifecycleContext.() -> Unit,
    ) : TypedAction<PresentationLifecycleContext> {
        /**
         * A presentation started. With a [timeout], the paywall must reach
         * [PaywallLoadingState.Ready] within it, or the load is tracked as failed and the
         * presentation ends as a webview failure. Replaces any earlier watch.
         */
        @OptIn(FlowPreview::class)
        data class WatchLoad(
            val timeout: Duration?,
        ) : Actions({
                (state.value.loadTimeout as? LoadTimeout.Watching)?.job?.cancel()
                if (timeout == null) {
                    update(Updates.StopWatchingLoad)
                } else {
                    val job =
                        loadWatchScope.launch {
                            val failure =
                                paywallStates
                                    .filter { it.loadingState == PaywallLoadingState.Ready }
                                    .map { Result.success(it.loadingState) }
                                    .timeout(timeout)
                                    .catch { emit(Result.failure(it)) }
                                    .first()
                                    .exceptionOrNull()
                            if (failure is TimeoutCancellationException) {
                                val info = paywallState().info
                                val msg =
                                    "Timeout triggered - paywall wasn't loaded in ${timeout.inWholeSeconds} seconds"
                                tracker(
                                    InternalSuperwallEvent.PaywallWebviewLoad(
                                        state =
                                            InternalSuperwallEvent.PaywallWebviewLoad.State.Fail(
                                                WebviewError.Timeout(msg),
                                                listOf(info.url.value),
                                            ),
                                        paywallInfo = info,
                                    ),
                                )
                                // The app set a deadline for the paywall to appear and it passed, so
                                // the paywall is closed rather than left on screen behind the app's
                                // own fallback. Same outcome as a webview that can't load.
                                effect(WebviewFailed)
                            }
                        }
                    update(Updates.WatchLoad(job))
                }
            })

        /** The presentation is being dismissed: stop watching the load. */
        object StopWatchingLoad : Actions({
            (state.value.loadTimeout as? LoadTimeout.Watching)?.job?.cancel()
            update(Updates.StopWatchingLoad)
        })

        /**
         * The webview can't load this paywall: every URL and retry is used up, or the app's
         * `paywalls.timeoutAfter` passed. Mirrors iOS's `handleWebViewFailure`: a paywall on
         * screen is dismissed as declined, one whose activity is still starting is dismissed
         * once it's created, and one off screen reloads the next time it's presented.
         */
        object WebviewFailed : Actions({
            updatePaywall(PaywallViewState.Updates.WebLoadingFailed)
            updatePaywall(PaywallViewState.Updates.WebLoadingExhausted)
            val paywall = paywallState()
            when (WebviewFailureOutcome.of(paywall)) {
                WebviewFailureOutcome.Dismiss -> {
                    Logger.debug(
                        LogLevel.error,
                        LogScope.paywallView,
                        "Webview failed to load - dismissing paywall ${paywall.paywall.identifier}",
                    )
                    dismissForWebviewFailure()
                }

                WebviewFailureOutcome.DismissOnceViewCreated ->
                    update(Updates.DeferFailureUntilViewCreated)

                WebviewFailureOutcome.ReloadWhenNextPresented ->
                    Logger.debug(
                        LogLevel.warn,
                        LogScope.paywallView,
                        "Webview failed to load off-screen - paywall ${paywall.paywall.identifier} " +
                            "will reload when next presented",
                    )
            }
        })

        /** The activity created the view: a failure that waited for it is handled now. */
        object ViewCreated : Actions({
            if (state.value.pendingFailure is PendingWebviewFailure.DismissOnceViewCreated) {
                update(Updates.ClearPendingFailure)
                immediate(WebviewFailed)
            }
        })

        /** A new presentation takes over the view: a failure deferred for the old one is dropped. */
        object ResetForNewPresentation : Actions({
            update(Updates.ClearPendingFailure)
        })
    }
}

/** What a webview failure does to the presentation, decided from the paywall's state. */
internal sealed class WebviewFailureOutcome {
    object Dismiss : WebviewFailureOutcome()

    object DismissOnceViewCreated : WebviewFailureOutcome()

    object ReloadWhenNextPresented : WebviewFailureOutcome()

    companion object {
        fun of(state: PaywallViewState): WebviewFailureOutcome =
            when {
                state.isPresented -> Dismiss
                // The activity for this presentation hasn't been created yet.
                state.viewCreatedCompletion != null -> DismissOnceViewCreated
                else -> ReloadWhenNextPresented
            }
    }
}

/** Context for [PresentationLifecycleState.Actions]: the paywall's state and the dismissal it can start. */
internal interface PresentationLifecycleContext :
    BaseContext<PresentationLifecycleState, PresentationLifecycleContext> {
    /** Where the load watch runs, off the actor so it never blocks other actions. */
    val loadWatchScope: CoroutineScope

    /** The paywall's state now. */
    val paywallState: () -> PaywallViewState

    /** The paywall's state as it changes. */
    val paywallStates: Flow<PaywallViewState>

    /** Applies an update to the paywall's state. */
    fun updatePaywall(update: PaywallViewState.Updates)

    /** Dismisses the paywall as declined with `WebViewFailedToLoad`. */
    fun dismissForWebviewFailure()
}

/**
 * Runs the presentation lifecycle of one [PaywallView] on a [SequentialActor], so a failure, the
 * view's creation and a new presentation apply in the order they happen.
 */
internal class PaywallPresentationLifecycle(
    override val scope: CoroutineScope,
    override val loadWatchScope: CoroutineScope,
    override val storage: Storage,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    override val paywallState: () -> PaywallViewState,
    override val paywallStates: Flow<PaywallViewState>,
    private val applyPaywallUpdate: (PaywallViewState.Updates) -> Unit,
    private val dismiss: () -> Unit,
    override val actor: StateActor<PresentationLifecycleContext, PresentationLifecycleState> =
        SequentialActor(PresentationLifecycleState(), scope),
) : PresentationLifecycleContext {
    override fun updatePaywall(update: PaywallViewState.Updates) = applyPaywallUpdate(update)

    override fun dismissForWebviewFailure() = dismiss()

    fun dispatch(action: PresentationLifecycleState.Actions) = effect(action)
}
