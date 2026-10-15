package com.superwall.sdk.paywall.view

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.superwall.sdk.R
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.webview.CustomTabsHelper
import com.superwall.sdk.paywall.view.webview.PaywallWebUI
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **Experimental.** Hosts a presented [PaywallView] inside the presenting Activity's own window
 * instead of in a [SuperwallPaywallActivity], for `PaywallOptions.PresentationHost.IN_WINDOW`.
 *
 * Adding a view produces no Activity lifecycle callbacks, so this drives the paywall's
 * presentation lifecycle itself, mirroring [SuperwallPaywallActivity]: showing it runs
 * onStart/onResume, [close] runs a finishing onPause/onStop/onDestroy, and the host Activity
 * pausing, stopping or resuming maps to a non-finishing pause/stop and a resume.
 *
 * Each presentation gets its own instance. Closing is idempotent, and a host whose view was
 * since taken over by a newer presentation never tears that presentation down.
 */
internal class InWindowPaywallHost private constructor(
    activity: Activity,
    private val view: PaywallView,
    val token: Long,
) {
    companion object {
        private val tokens = AtomicLong(0)
        private val mainScope = MainScope()

        // Durations and scrim match SuperwallPaywallActivity's bottom sheet and popup.
        private const val ENTER_MS = 300L
        private const val EXIT_MS = 300L
        private const val SCRIM_ENTER_MS = 600L
        private val SCRIM_COLOR = Color.argb(200, 0, 0, 0)

        @Volatile
        internal var current: InWindowPaywallHost? = null
            private set

        internal fun show(
            presenter: Activity,
            view: PaywallView,
            presentationStyle: PaywallPresentationStyle?,
        ): Result<InWindowPaywallHost> {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "In-window paywalls must be presented on the main thread."
            }
            if (!presenter.canPresentPaywall()) {
                view.clearActivityLaunchState()
                return Result.failure(
                    PaywallActivityLaunchException(
                        "The presenter Activity is no longer started or is finishing.",
                    ),
                )
            }
            val content =
                presenter.findViewById<ViewGroup>(android.R.id.content)
                    ?: (presenter.window?.decorView as? ViewGroup)
                    ?: run {
                        view.clearActivityLaunchState()
                        return Result.failure(
                            PaywallActivityLaunchException("The presenter Activity has no content view."),
                        )
                    }

            current?.supersededBy(view)
            val host = InWindowPaywallHost(presenter, view, tokens.incrementAndGet())
            return try {
                host.attach(content, presentationStyle)
                current = host
                Result.success(host)
            } catch (error: RuntimeException) {
                host.detach()
                view.clearActivityLaunchState()
                Result.failure(
                    PaywallActivityLaunchException("Unable to show the paywall in-window.", error),
                )
            }
        }
    }

    private val activityRef = WeakReference(activity)
    private val closed = AtomicBoolean(false)
    private val container = Container(activity)
    private var backCallback: Any? = null

    /** Plays this presentation's exit animation, then calls `onEnd`. */
    private var exitAnimation: ((onEnd: () -> Unit) -> Unit)? = null

    val isClosed: Boolean
        get() = closed.get()

    private fun attach(
        content: ViewGroup,
        presentationStyle: PaywallPresentationStyle?,
    ) {
        val activity = activityRef.get() ?: error("Host activity is gone")

        // Game engines (Unity among them) ship their activity with hardwareAccelerated="false".
        // A WebView in a software-rendered window neither redraws nor handles input, so enable
        // acceleration for the host window. Android allows enabling it at runtime; never disabling.
        activity.window?.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )

        (view.parent as? ViewGroup)?.removeView(view)
        view.inWindowHost = this
        view.encapsulatingActivity = WeakReference(activity)
        view.webView.enableBackgroundRendering()
        view.webView.attach(view)

        content.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Bottom sheet and popup layouts use Material attributes the host's theme may lack.
        val themed = ContextThemeWrapper(activity, R.style.Theme_Superwall_InWindow)
        exitAnimation =
            when (presentationStyle) {
                is PaywallPresentationStyle.Drawer ->
                    attachBottomSheet(themed, isModal = false, presentationStyle.height, presentationStyle.cornerRadius)

                is PaywallPresentationStyle.Modal ->
                    attachBottomSheet(themed, isModal = true, height = 0.0, cornerRadius = 0.0)

                is PaywallPresentationStyle.Popup ->
                    attachPopup(themed, presentationStyle.height, presentationStyle.width, presentationStyle.cornerRadius)

                is PaywallPresentationStyle.Push -> attachFullscreen(Transition.PUSH)
                is PaywallPresentationStyle.Fullscreen -> attachFullscreen(Transition.RISE)
                is PaywallPresentationStyle.FullscreenNoAnimation,
                is PaywallPresentationStyle.None,
                null,
                -> attachFullscreen(Transition.NONE)
            }
        ViewCompat.requestApplyInsets(container)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackCallback(activity)
        }
        activity.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)

        // What SuperwallPaywallActivity's onStart and onResume do.
        view.beforeViewCreated()
        view.webView.onResume()
        view.onViewCreated()
        requestFocus()
    }

    /**
     * Ends this presentation: the finishing onPause/onStop/onDestroy of [SuperwallPaywallActivity].
     * Safe to call from any thread and more than once; the host Activity is never finished.
     */
    fun close() = close(animated = true)

    private fun close(animated: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        mainScope.launch {
            unregister()
            val exit = exitAnimation.takeIf { animated && container.isAttachedToWindow }
            if (exit == null) {
                tearDown()
            } else {
                // Like SuperwallPaywallActivity.finish(): animate out, then run the teardown.
                exit(onceOnMain { mainScope.launch { tearDown() } })
            }
        }
    }

    private suspend fun tearDown() {
        if (view.inWindowHost === this) {
            view.webView.onPause()
            view.beforeOnDestroy(forceCleanup = true)
            view.destroyed(forceCleanup = true)
            // destroyed() can suspend, so check that no newer presentation took the view.
            if (view.inWindowHost === this) {
                view.inWindowHost = null
                view.encapsulatingActivity = null
                view.cleanup()
            }
        }
        removeContainer()
    }

    /** Wraps [block] so it runs once, and also after a timeout should an animation never end. */
    private fun onceOnMain(block: () -> Unit): () -> Unit {
        val ran = AtomicBoolean(false)
        val once = { if (ran.compareAndSet(false, true)) block() }
        container.postDelayed(once, EXIT_MS * 2)
        return once
    }

    /** The host Activity went away without the paywall being dismissed. */
    private fun closeForHostGone() {
        if (closed.get()) return
        if (view.state.paywallResult == null) {
            view.updateState(
                PaywallViewState.Updates.InitiateDismiss(
                    PaywallResult.Declined(),
                    PaywallCloseReason.SystemLogic,
                    view.state.dismissCompletionBlock,
                ),
            )
        }
        close(animated = false)
    }

    /** A newer presentation is starting while this one is still on screen. */
    private fun supersededBy(newView: PaywallView) {
        if (newView === view) {
            // The view moves to the new host and stays presented; only drop this host's chrome.
            detach()
        } else {
            close()
        }
    }

    /** Removes this host without running any presentation lifecycle on the view. */
    private fun detach() {
        closed.set(true)
        unregister()
        if (view.inWindowHost === this) {
            view.inWindowHost = null
        }
        removeContainer()
    }

    private fun unregister() {
        val activity = activityRef.get()
        activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterBackCallback(activity)
        }
    }

    private fun removeContainer() {
        if (view.parent === container) {
            container.removeView(view)
        }
        (container.parent as? ViewGroup)?.removeView(container)
        if (current === this) {
            current = null
        }
    }

    private fun requestFocus() {
        // Posted so it lands after the host Activity's own onResume work (e.g. a game engine
        // taking focus back for its surface), keeping key events, including back, routed here.
        container.post {
            if (closed.get()) return@post
            view.webView.onView { requestFocus() }
            if (!container.hasFocus()) container.requestFocus()
        }
    }

    //region Layouts

    private enum class Transition { NONE, RISE, PUSH }

    /** Fullscreen, Push and no-animation styles: the paywall fills the window, inset from the system bars. */
    private fun attachFullscreen(transition: Transition): (onEnd: () -> Unit) -> Unit {
        container.setBackgroundColor(view.backgroundColor)
        container.addView(view, matchParent())
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, windowInsets ->
            val insets =
                windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val width = container.resources.displayMetrics.widthPixels.toFloat()
        val rise = container.resources.displayMetrics.heightPixels * 0.05f
        when (transition) {
            Transition.NONE -> return { onEnd -> onEnd() }
            Transition.RISE -> {
                container.alpha = 0f
                container.translationY = rise
                container.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS)
                    .setInterpolator(DecelerateInterpolator()).start()
                return { onEnd ->
                    container.animate().alpha(0f).translationY(rise).setDuration(EXIT_MS)
                        .withEndAction(onEnd).start()
                }
            }
            Transition.PUSH -> {
                container.translationX = width
                container.animate().translationX(0f).setDuration(ENTER_MS)
                    .setInterpolator(DecelerateInterpolator()).start()
                return { onEnd ->
                    container.animate().translationX(width).setDuration(EXIT_MS)
                        .withEndAction(onEnd).start()
                }
            }
        }
    }

    /**
     * Drawer and Modal: the bottom sheet from [SuperwallPaywallActivity]. A Drawer opens half
     * expanded to its height; a Modal opens fully expanded, 5% below the top. Tapping the dimmed
     * backdrop or dragging the sheet away dismisses the paywall as a manual close.
     */
    private fun attachBottomSheet(
        themed: Context,
        isModal: Boolean,
        height: Double,
        cornerRadius: Double,
    ): (onEnd: () -> Unit) -> Unit {
        val coordinator =
            LayoutInflater.from(themed).inflate(R.layout.activity_bottom_sheet, container, false) as CoordinatorLayout
        val sheet = coordinator.findViewById<FrameLayout>(R.id.container)
        container.addView(coordinator)
        if (isModal) {
            coordinator.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = (container.resources.displayMetrics.heightPixels * 0.05).toInt()
            }
        }
        sheet.addView(view, matchParent())
        sheet.clipToOutline = true
        sheet.outlineProvider = roundedOutline(cornerRadius)
        sheet.setOnClickListener { /* consume, so only the backdrop dismisses */ }
        coordinator.setOnClickListener { dismissByUser() }
        // Keep content above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val scrim = fadeInScrim()

        val behavior = BottomSheetBehavior.from(sheet)
        if (!isModal) {
            val normalizedHeight = (if (height > 1.0) height / 100 else height).toFloat()
            // Clamp to (0, 1): 0.0 is STATE_COLLAPSED and 1.0 is STATE_EXPANDED.
            behavior.halfExpandedRatio = normalizedHeight.coerceIn(0.01f, 0.99f)
        }
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        val target = if (isModal) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED
        // Expand after layout so the slide-up runs on all devices.
        coordinator.post { if (!closed.get()) behavior.state = target }

        var webViewScroll = 0
        if (isModal) {
            view.webView.onScrollChangeListener =
                object : PaywallWebUI.OnScrollChangeListener {
                    override fun onScrollChanged(
                        currentHorizontalScroll: Int,
                        currentVerticalScroll: Int,
                        oldHorizontalScroll: Int,
                        oldcurrentVerticalScroll: Int,
                    ) {
                        webViewScroll = currentVerticalScroll
                    }
                }
        }
        val callback =
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    bottomSheet: View,
                    newState: Int,
                ) {
                    when {
                        // An expanded modal whose page is scrolled can't be dragged down.
                        isModal && newState == BottomSheetBehavior.STATE_DRAGGING && webViewScroll > 0 ->
                            behavior.state = BottomSheetBehavior.STATE_EXPANDED
                        // A modal skips the half-expanded state.
                        isModal && newState == BottomSheetBehavior.STATE_HALF_EXPANDED ->
                            behavior.state = BottomSheetBehavior.STATE_EXPANDED
                        newState == BottomSheetBehavior.STATE_HIDDEN -> dismissByUser()
                    }
                }

                override fun onSlide(
                    bottomSheet: View,
                    slideOffset: Float,
                ) {
                    view.webView.onView { behavior.isDraggable = !canScrollVertically(-1) }
                }
            }
        behavior.addBottomSheetCallback(callback)

        return { onEnd ->
            // Removed first so hiding the sheet below doesn't dismiss the paywall again.
            behavior.removeBottomSheetCallback(callback)
            scrim.cancel()
            fadeOutScrim()
            if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                onEnd()
            } else {
                behavior.addBottomSheetCallback(
                    object : BottomSheetBehavior.BottomSheetCallback() {
                        override fun onStateChanged(
                            bottomSheet: View,
                            newState: Int,
                        ) {
                            if (newState == BottomSheetBehavior.STATE_HIDDEN) onEnd()
                        }

                        override fun onSlide(
                            bottomSheet: View,
                            slideOffset: Float,
                        ) = Unit
                    },
                )
                coordinator.post { behavior.state = BottomSheetBehavior.STATE_HIDDEN }
            }
        }
    }

    /** Popup: a centred, rounded card sized as a percentage of the window, over a dimmed backdrop. */
    private fun attachPopup(
        themed: Context,
        height: Double,
        width: Double,
        cornerRadius: Double,
    ): (onEnd: () -> Unit) -> Unit {
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_popup, container, false) as ConstraintLayout
        container.addView(root)
        // Centre the popup within the safe area.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val card = root.findViewById<FrameLayout>(R.id.container)
        card.updateLayoutParams<ConstraintLayout.LayoutParams> {
            this.width = 0 // MATCH_CONSTRAINT
            this.height = 0 // MATCH_CONSTRAINT
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentWidth = (width.toFloat() / 100f).coerceIn(0.01f, 1f)
            matchConstraintPercentHeight = (height.toFloat() / 100f).coerceIn(0.01f, 1f)
        }
        card.clipToOutline = true
        card.outlineProvider = roundedOutline(cornerRadius)
        card.setOnClickListener { /* consume */ }
        card.addView(view, matchParent())
        fadeInScrim()

        card.scaleX = 0f
        card.scaleY = 0f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ENTER_MS)
            .setInterpolator(OvershootInterpolator(1.1f)).start()

        return { onEnd ->
            fadeOutScrim()
            card.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(EXIT_MS)
                .withEndAction(onEnd).start()
        }
    }

    private fun dismissByUser() {
        if (closed.get()) return
        view.dismiss(
            result = PaywallResult.Declined(),
            closeReason = PaywallCloseReason.ManualClose,
        )
    }

    private fun fadeInScrim(): Animator =
        ValueAnimator.ofObject(ArgbEvaluator(), Color.TRANSPARENT, SCRIM_COLOR).apply {
            duration = SCRIM_ENTER_MS
            addUpdateListener { container.setBackgroundColor(it.animatedValue as Int) }
            start()
        }

    private fun fadeOutScrim() {
        ValueAnimator.ofObject(ArgbEvaluator(), SCRIM_COLOR, Color.TRANSPARENT).apply {
            duration = EXIT_MS
            addUpdateListener { container.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    // A custom outline so clipToOutline rounds corners reliably on API 30-32 too.
    private fun roundedOutline(cornerRadius: Double): ViewOutlineProvider {
        val radius = cornerRadius.toFloat() * container.resources.displayMetrics.density
        return object : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

    //endregion

    //region Back

    private fun onBackPressed() {
        if (closed.get()) return
        val consumedByApp =
            isBackPressConsumedByApp(
                rerouteBackButton = view.state.paywall.rerouteBackButton,
                consumedByApp = {
                    view.factory
                        .makeSuperwallOptions()
                        .paywalls.onBackPressed
                        ?.let { it(view.info) }
                        ?: false
                },
            )
        if (consumedByApp) {
            return
        }
        view.backButtonPressed()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback(activity: Activity) {
        // Only delivered when the app opted into OnBackInvokedCallback; otherwise the back key
        // reaches [Container.dispatchKeyEvent] instead.
        val callback = OnBackInvokedCallback { onBackPressed() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback(activity: Activity) {
        (backCallback as? OnBackInvokedCallback)?.let {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        backCallback = null
    }

    //endregion

    /**
     * Opens web checkout in a Custom Tab. The activity-hosted path uses an activity result
     * launcher, which can't be registered on an already started host, so this is a plain launch
     * with no result: the sheet counts as closed once the host Activity resumes.
     */
    fun launchCheckout(url: String) {
        val activity = activityRef.get()
        if (activity == null) {
            view.isPaymentSheetPresented = false
            return
        }
        try {
            val customTabs =
                CustomTabsIntent
                    .Builder()
                    .setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .build()
            CustomTabsHelper.getPackageNameToUse(activity)?.let { customTabs.intent.setPackage(it) }
            customTabs.launchUrl(activity, url.toUri())
        } catch (e: Throwable) {
            Logger.debug(
                LogLevel.error,
                LogScope.paywallView,
                "Unable to open checkout from an in-window paywall",
                error = e,
            )
            view.isPaymentSheetPresented = false
        }
    }

    private fun isHost(activity: Activity) = !closed.get() && activity === activityRef.get()

    private val lifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (!isHost(activity)) return
                view.beforeViewCreated()
            }

            override fun onActivityResumed(activity: Activity) {
                if (!isHost(activity)) return
                // A web checkout opened by launchCheckout() has closed by the time we're back.
                view.isPaymentSheetPresented = false
                view.webView.onResume()
                view.onViewCreated()
                container.bringToFront()
                requestFocus()
            }

            override fun onActivityPaused(activity: Activity) {
                if (!isHost(activity)) return
                if (activity.isFinishing) {
                    closeForHostGone()
                    return
                }
                view.webView.onPause()
                view.beforeOnDestroy(forceCleanup = false)
            }

            override fun onActivityStopped(activity: Activity) {
                if (!isHost(activity)) return
                if (activity.isFinishing) {
                    closeForHostGone()
                    return
                }
                mainScope.launch {
                    view.destroyed(forceCleanup = false)
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (!isHost(activity)) return
                closeForHostGone()
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit
        }

    /** Holds the paywall above the host's content, swallowing touches and the back key. */
    private inner class Container(
        context: Context,
    ) : FrameLayout(context) {
        init {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    onBackPressed()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
