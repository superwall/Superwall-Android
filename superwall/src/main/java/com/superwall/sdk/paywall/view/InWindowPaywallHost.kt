package com.superwall.sdk.paywall.view

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.webview.CustomTabsHelper
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

    val isClosed: Boolean
        get() = closed.get()

    private fun attach(
        content: ViewGroup,
        presentationStyle: PaywallPresentationStyle?,
    ) {
        val activity = activityRef.get() ?: error("Host activity is gone")
        when (presentationStyle) {
            null,
            is PaywallPresentationStyle.Fullscreen,
            is PaywallPresentationStyle.FullscreenNoAnimation,
            is PaywallPresentationStyle.Push,
            is PaywallPresentationStyle.None,
            -> Unit

            else ->
                Logger.debug(
                    LogLevel.info,
                    LogScope.paywallView,
                    "In-window paywalls are shown full-screen; ignoring presentation style $presentationStyle",
                )
        }

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

        container.setBackgroundColor(view.backgroundColor)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Keep content out from under the system bars without touching the host window's flags.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, windowInsets ->
            val insets =
                windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        content.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
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
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainScope.launch {
            unregister()
            if (view.inWindowHost === this@InWindowPaywallHost) {
                view.webView.onPause()
                view.beforeOnDestroy(forceCleanup = true)
                view.destroyed(forceCleanup = true)
                // destroyed() can suspend, so check that no newer presentation took the view.
                if (view.inWindowHost === this@InWindowPaywallHost) {
                    view.inWindowHost = null
                    view.encapsulatingActivity = null
                    view.cleanup()
                }
            }
            removeContainer()
        }
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
        close()
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
