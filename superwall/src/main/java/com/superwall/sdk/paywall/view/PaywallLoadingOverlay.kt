package com.superwall.sdk.paywall.view

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * What the shimmer and purchase spinner of one [PaywallView] last reacted to, and the hide that
 * waits for the paywall's presentation delay. Owned by [PaywallLoadingOverlay].
 */
internal data class LoadingOverlayState(
    val lastHandled: PaywallLoadingState = PaywallLoadingState.Unknown,
    val pendingHide: PendingHide = PendingHide.None,
) {
    /** A hide of the overlay that waits for `presentation.delay` before revealing the paywall. */
    sealed class PendingHide {
        object None : PendingHide()

        data class Delayed(
            val job: Job,
        ) : PendingHide()
    }

    // -----------------------------------------------------------------------
    // Pure state mutations — (LoadingOverlayState) -> LoadingOverlayState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (LoadingOverlayState) -> LoadingOverlayState,
    ) : Reducer<LoadingOverlayState> {
        /** [loadingState] is now the state the overlay shows; any waiting hide is dropped. */
        data class Handled(
            val loadingState: PaywallLoadingState,
        ) : Updates({ it.copy(lastHandled = loadingState, pendingHide = PendingHide.None) })

        data class HideAfterDelay(
            val job: Job,
        ) : Updates({ it.copy(pendingHide = PendingHide.Delayed(job)) })

        object HideDone : Updates({ it.copy(pendingHide = PendingHide.None) })
    }

    // -----------------------------------------------------------------------
    // Actions — async work via LoadingOverlayContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend LoadingOverlayContext.() -> Unit,
    ) : TypedAction<LoadingOverlayContext> {
        /**
         * The paywall's loading state changed to [current]. Only a paywall on screen reacts: the
         * spinner for a purchase or manual loading, the shimmer while the URL loads, and both
         * hidden once ready. The first reveal after the shimmer waits for `presentation.delay`.
         */
        data class LoadingStateChanged(
            val current: PaywallLoadingState,
            val isPresented: Boolean,
        ) : Actions({
                if (isPresented) {
                    val previous = state.value.lastHandled
                    (state.value.pendingHide as? PendingHide.Delayed)?.job?.cancel()
                    update(Updates.Handled(current))
                    when (OverlayChange.of(previous, current)) {
                        OverlayChange.None -> Unit
                        OverlayChange.ShowSpinner -> immediate(ShowSpinner)
                        OverlayChange.ShowShimmer -> showShimmer()
                        OverlayChange.RevealAfterDelay -> {
                            val job =
                                delayScope.launch {
                                    delay(presentationDelayMillis())
                                    effect(Reveal)
                                }
                            update(Updates.HideAfterDelay(job))
                        }
                        OverlayChange.Reveal -> immediate(Reveal)
                    }
                }
            })

        /** Shows the purchase spinner, unless the app chose no transaction background view. */
        object ShowSpinner : Actions({
            if (transactionBackgroundView() == PaywallOptions.TransactionBackgroundView.SPINNER) {
                showSpinner()
            }
        })

        /** Hides the spinner and the shimmer, ends the shimmer once and resets the crash retries. */
        object Reveal : Actions({
            update(Updates.HideDone)
            hideSpinner()
            hideShimmer()
            immediate(EndShimmer)
            updatePaywall(PaywallViewState.Updates.ResetCrashRetry)
        })

        /**
         * The shimmer started for a presentation: tracks it. The view records the start in the
         * paywall's state itself, at once, since the open event reads it.
         */
        object TrackShimmerStart : Actions({
            val paywall = paywallState().paywall
            val event =
                InternalSuperwallEvent.ShimmerLoad(
                    state = InternalSuperwallEvent.ShimmerLoad.State.Started,
                    paywallId = paywall.identifier,
                    visibleDuration = null,
                    preloadingEnabled = preloadingEnabled(),
                    delay = paywall.presentation.delay.toDouble(),
                )
            delayScope.launch { tracker(event) }
        })

        /**
         * The shimmer went away: records its end and tracks how long it was visible, once per
         * shimmer. Like iOS, the duration runs from the paywall's open; it's 0 when the shimmer
         * never started (a preloaded paywall) or the open isn't recorded yet.
         */
        object EndShimmer : Actions({
            val paywallState = paywallState()
            if (paywallState.paywall.shimmerLoadingInfo.endAt == null) {
                val visibleDuration =
                    shimmerVisibleDuration(
                        shimmerStartAt = paywallState.paywall.shimmerLoadingInfo.startAt,
                        lastOpen = paywallState.lastOpen,
                        now = Date(),
                    )
                updatePaywall(PaywallViewState.Updates.ShimmerEnded)
                val event =
                    InternalSuperwallEvent.ShimmerLoad(
                        state = InternalSuperwallEvent.ShimmerLoad.State.Complete,
                        paywallId = paywallState.paywall.identifier,
                        visibleDuration = visibleDuration,
                        delay = paywallState.paywall.presentation.delay.toDouble(),
                        preloadingEnabled = preloadingEnabled(),
                    )
                delayScope.launch { tracker(event) }
            }
        })
    }
}

/** What the overlay does when the loading state goes from one value to the next. */
internal sealed class OverlayChange {
    object None : OverlayChange()

    object ShowSpinner : OverlayChange()

    object ShowShimmer : OverlayChange()

    /** The first reveal after the shimmer, which waits for `presentation.delay`. */
    object RevealAfterDelay : OverlayChange()

    /** Any later reveal, such as hiding the purchase spinner, which happens at once. */
    object Reveal : OverlayChange()

    companion object {
        fun of(
            previous: PaywallLoadingState,
            current: PaywallLoadingState,
        ): OverlayChange =
            when (current) {
                is PaywallLoadingState.Unknown -> None
                is PaywallLoadingState.LoadingPurchase, is PaywallLoadingState.ManualLoading -> ShowSpinner
                is PaywallLoadingState.LoadingURL -> ShowShimmer
                is PaywallLoadingState.Ready ->
                    if (previous is PaywallLoadingState.LoadingURL || previous is PaywallLoadingState.Unknown) {
                        RevealAfterDelay
                    } else {
                        Reveal
                    }
            }
    }
}

/**
 * How long the shimmer was visible, in seconds, from the paywall's open to [now]; 0 without a
 * shimmer start or an open. Kept as it was before: the millisecond difference is read as seconds.
 */
internal fun shimmerVisibleDuration(
    shimmerStartAt: Date?,
    lastOpen: Date?,
    now: Date,
): Double =
    if (shimmerStartAt != null && lastOpen != null) {
        (now.time - lastOpen.time).seconds.toDouble(DurationUnit.SECONDS)
    } else {
        0.0
    }

/** Context for [LoadingOverlayState.Actions]: the overlay views and the paywall state they reflect. */
internal interface LoadingOverlayContext : BaseContext<LoadingOverlayState, LoadingOverlayContext> {
    /** Where the delayed hide waits and events are sent, off the actor. */
    val delayScope: CoroutineScope

    /** The paywall's state now. */
    val paywallState: () -> PaywallViewState

    /** The app's choice of view behind a transaction. */
    val transactionBackgroundView: () -> PaywallOptions.TransactionBackgroundView?

    /** Whether paywalls are preloaded on this device, as reported with shimmer events. */
    val preloadingEnabled: () -> Boolean

    /** The paywall's `presentation.delay`, in milliseconds. */
    fun presentationDelayMillis(): Long = paywallState().paywall.presentation.delay

    /** Applies an update to the paywall's state. */
    fun updatePaywall(update: PaywallViewState.Updates)

    fun showSpinner()

    fun hideSpinner()

    fun showShimmer()

    fun hideShimmer()
}

/**
 * Drives the shimmer and purchase spinner of one [PaywallView] on a [SequentialActor], so loading
 * states apply in the order they arrive and a later one cancels a reveal still waiting.
 */
internal class PaywallLoadingOverlay(
    override val scope: CoroutineScope,
    override val delayScope: CoroutineScope,
    override val storage: Storage,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    override val paywallState: () -> PaywallViewState,
    override val transactionBackgroundView: () -> PaywallOptions.TransactionBackgroundView?,
    override val preloadingEnabled: () -> Boolean,
    private val applyPaywallUpdate: (PaywallViewState.Updates) -> Unit,
    private val views: () -> OverlayViews,
    override val actor: StateActor<LoadingOverlayContext, LoadingOverlayState> =
        SequentialActor(LoadingOverlayState(), scope),
) : LoadingOverlayContext {
    /** The shimmer and spinner views the paywall currently holds, if any. */
    data class OverlayViews(
        val shimmer: PaywallShimmerView?,
        val spinner: PaywallPurchaseLoadingView?,
    )

    override fun updatePaywall(update: PaywallViewState.Updates) = applyPaywallUpdate(update)

    override fun showSpinner() {
        views().spinner?.showLoading()
    }

    override fun hideSpinner() {
        views().spinner?.hideLoading()
    }

    override fun showShimmer() {
        views().shimmer?.showShimmer()
    }

    override fun hideShimmer() {
        views().shimmer?.hideShimmer()
    }

    fun dispatch(action: LoadingOverlayState.Actions) = effect(action)
}
