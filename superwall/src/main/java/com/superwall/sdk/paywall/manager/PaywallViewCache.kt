package com.superwall.sdk.paywall.manager

import android.content.Context
import android.view.View
import androidx.annotation.ColorRes
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.primitives.StoreContext
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.models.paywall.PaywallIdentifier
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.view.LoadingView
import com.superwall.sdk.paywall.view.PaywallPurchaseLoadingView
import com.superwall.sdk.paywall.view.PaywallShimmerView
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.ShimmerView
import com.superwall.sdk.paywall.view.ViewStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Source-of-truth state for the paywall view cache.
 *
 * Mirrors the underlying [ViewStorage] but is owned by a [SequentialActor] so
 * mutations are FIFO-serialized and reads via `actor.state.value` always
 * return a consistent snapshot.
 */
data class PaywallCacheState(
    val views: Map<String, View> = emptyMap(),
    val activePaywallVcKey: String? = null,
) {
    val paywallViews: List<PaywallView>
        get() = views.values.filterIsInstance<PaywallView>()

    fun viewAt(key: String): View? = views[key]

    val activePaywallView: PaywallView?
        get() = activePaywallVcKey?.let { views[it] as? PaywallView }

    internal sealed class Updates(
        override val reduce: (PaywallCacheState) -> PaywallCacheState,
    ) : Reducer<PaywallCacheState> {
        data class StoreView(
            val key: String,
            val view: View,
        ) : Updates({ it.copy(views = it.views + (key to view)) })

        data class RemoveView(
            val key: String,
        ) : Updates({ it.copy(views = it.views - key) })

        data class SetActiveKey(
            val key: String?,
        ) : Updates({ it.copy(activePaywallVcKey = key) })

        object RemoveAllExceptActive : Updates({ state ->
            val active = state.activePaywallVcKey
            val kept = if (active != null) state.views.filterKeys { it == active } else emptyMap()
            state.copy(views = kept)
        })

        data class Hydrate(
            val views: Map<String, View>,
        ) : Updates({ it.copy(views = views) })
    }

    internal sealed class Actions(
        override val execute: suspend PaywallCacheContext.() -> Unit,
    ) : TypedAction<PaywallCacheContext> {
        /** Atomically write a paywall view to state and viewStorage. */
        data class Save(
            val identifier: PaywallIdentifier,
            val view: PaywallView,
        ) : Actions({
            val key = PaywallCacheLogic.key(identifier, deviceHelper.locale)
            viewStorage.storeView(key, view)
            update(Updates.StoreView(key, view))
        })

        data class Remove(
            val identifier: PaywallIdentifier,
        ) : Actions({
            val key = PaywallCacheLogic.key(identifier, deviceHelper.locale)
            viewStorage.removeView(key)
            update(Updates.RemoveView(key))
        })

        object RemoveAllExceptActive : Actions({
            val active = state.value.activePaywallVcKey
            state.value.views.keys
                .filter { it != active }
                .forEach { viewStorage.removeView(it) }
            update(Updates.RemoveAllExceptActive)
        })

        /**
         * Get-or-create the LoadingView. Atomic: only one factory invocation
         * across concurrent callers because actions are FIFO-serialized.
         *
         * The factory runs on the actor's consumer thread and must not dispatch
         * to [Dispatchers.Main]: callers block on the result via `runBlocking`,
         * usually from the main thread, so a main hop here would deadlock.
         */
        data class EnsureLoadingView(
            val factory: () -> PaywallPurchaseLoadingView,
        ) : Actions({
            if (state.value.views[LoadingView.TAG] !is PaywallPurchaseLoadingView) {
                val v = factory()
                viewStorage.storeView(LoadingView.TAG, v as View)
                update(Updates.StoreView(LoadingView.TAG, v))
            }
        })

        data class EnsureShimmerView(
            val factory: () -> PaywallShimmerView,
        ) : Actions({
            if (state.value.views[ShimmerView.TAG] !is PaywallShimmerView) {
                val v = factory()
                viewStorage.storeView(ShimmerView.TAG, v as View)
                update(Updates.StoreView(ShimmerView.TAG, v))
            }
        })
    }
}

/**
 * Dependencies available to [PaywallCacheState.Actions].
 *
 * [PaywallViewCache] implements this directly — actions receive `this` as
 * their context, with no intermediate object.
 */
interface PaywallCacheContext : StoreContext<PaywallCacheState, PaywallCacheContext> {
    val viewStorage: ViewStorage
    val deviceHelper: DeviceHelper
    val activityProvider: ActivityProvider
    val appCtx: Context

    val ctx: Context
        get() = activityProvider.getCurrentActivity() ?: appCtx
}

/**
 * Cache for paywall, loading, and shimmer views.
 *
 * State is owned by a [SequentialActor] — every mutation is enqueued through a
 * single FIFO consumer, so `state.value` always reflects the latest committed
 * data and there are no races between save/get, remove/save, or concurrent
 * acquire calls. [ViewStorage] is kept as a write-through mirror because
 * external readers (SuperwallPaywallActivity, DebugView) access it directly.
 */
class PaywallViewCache(
    override val appCtx: Context,
    override val viewStorage: ViewStorage,
    override val activityProvider: ActivityProvider,
    override val deviceHelper: DeviceHelper,
    @ColorRes private val loadingColor: Int? = null,
    override val actor: SequentialActor<PaywallCacheContext, PaywallCacheState> =
        SequentialActor(PaywallCacheState(), CoroutineScope(Dispatchers.IO)),
) : PaywallCacheContext {
    override val scope: CoroutineScope get() = actor.scope

    init {
        // Hydrate from any pre-existing entries in viewStorage (e.g. survived
        // an Activity recreation via the ViewStorageViewModel).
        val existing = viewStorage.views.toMap()
        if (existing.isNotEmpty()) {
            actor.update(PaywallCacheState.Updates.Hydrate(existing))
        }
    }

    val entries: Map<String, View>
        get() = state.value.views

    var activePaywallVcKey: String?
        get() = state.value.activePaywallVcKey
        set(value) {
            actor.update(PaywallCacheState.Updates.SetActiveKey(value))
        }

    val activePaywallView: PaywallView?
        get() = state.value.activePaywallView

    fun getAllPaywallViews(): List<PaywallView> = state.value.paywallViews

    fun getPaywallView(key: String): PaywallView? = state.value.viewAt(key) as? PaywallView

    suspend fun save(
        paywallView: PaywallView,
        identifier: PaywallIdentifier,
    ) {
        immediate(PaywallCacheState.Actions.Save(identifier, paywallView))
    }

    suspend fun removePaywallView(identifier: PaywallIdentifier) {
        immediate(PaywallCacheState.Actions.Remove(identifier))
    }

    suspend fun removeAll() {
        immediate(PaywallCacheState.Actions.RemoveAllExceptActive)
    }

    /**
     * Synchronous because [PaywallView.present] is non-suspend.
     *
     * Fast path: if state already holds the canonical view, return it without
     * touching the actor queue. Slow path (cold start, or after [removeAll]
     * evicted the tag): block on the actor's `immediate` so exactly one
     * factory invocation happens across concurrent callers. The View is
     * constructed on the actor thread; it is only attached to a hierarchy
     * later, on the main thread, by [PaywallView].
     */
    fun acquireLoadingView(): PaywallPurchaseLoadingView {
        (state.value.views[LoadingView.TAG] as? PaywallPurchaseLoadingView)?.let { return it }
        return runBlocking {
            immediate(
                PaywallCacheState.Actions.EnsureLoadingView {
                    LoadingView(ctx, loadingColor = loadingColor)
                },
            )
            state.value.views[LoadingView.TAG] as PaywallPurchaseLoadingView
        }
    }

    fun acquireShimmerView(): PaywallShimmerView {
        (state.value.views[ShimmerView.TAG] as? PaywallShimmerView)?.let { return it }
        return runBlocking {
            immediate(
                PaywallCacheState.Actions.EnsureShimmerView {
                    ShimmerView(ctx)
                },
            )
            state.value.views[ShimmerView.TAG] as PaywallShimmerView
        }
    }
}
