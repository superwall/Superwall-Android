package com.superwall.sdk.paywall.view.webview

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.toColorInt
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.superwall.sdk.R
import com.superwall.sdk.Superwall
import com.superwall.sdk.game.dispatchKeyEvent
import com.superwall.sdk.game.dispatchMotionEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger

class CheckoutWebView(
    context: Context,
    private val onFinishedLoading: ((url: String) -> Unit)? = null,
    private val onDismiss: (() -> Unit)? = null,
) : WebView(context) {
    var onScrollChangeListener: OnScrollChangeListener? = null
    var onRenderProcessCrashed: ((RenderProcessGoneDetail) -> Unit) = {
        Logger.debug(
            LogLevel.error,
            LogScope.paywallView,
            "WebView crashed: $it",
        )
    }

    private var bottomSheetContainer: CoordinatorLayout? = null
    private var bottomSheetFrame: FrameLayout? = null
    private var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>? = null

    private companion object ChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // Don't log anything
            return true
        }
    }

    private var lastWebViewClient: WebViewClient? = null
    private var lastLoadedUrl: String? = null

    internal fun prepareWebview() {
        val webSettings = this.settings
        webSettings.javaScriptEnabled = true
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.domStorageEnabled = true
        webSettings.textZoom = 100
        // Enable inline media playback, requires API level 17
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.mediaPlaybackRequiresUserGesture = false
        }

        this.setBackgroundColor(Color.TRANSPARENT)
        this.webChromeClient = ChromeClient
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection = BaseInputConnection(this, false)

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || !Superwall.instance.options.isGameControllerEnabled) {
            return super.dispatchKeyEvent(event)
        }
        Superwall.instance.dispatchKeyEvent(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || !Superwall.instance.options.isGameControllerEnabled) {
            return super.dispatchGenericMotionEvent(event)
        }
        Superwall.instance.dispatchMotionEvent(event)
        return true
    }

    override fun loadUrl(url: String) {
        super.loadUrl(url)
    }

    override fun onScrollChanged(
        horizontalOrigin: Int,
        verticalOrigin: Int,
        oldHorizontal: Int,
        oldVertical: Int,
    ) {
        super.onScrollChanged(horizontalOrigin, verticalOrigin, oldHorizontal, oldVertical)
        onScrollChangeListener?.onScrollChanged(
            horizontalOrigin,
            verticalOrigin,
            oldHorizontal,
            oldVertical,
        )
    }

    override fun destroy() {
        onScrollChangeListener = null
        dismissBottomSheet()
        super.destroy()
    }

    fun presentAsBottomSheet(
        activity: Activity,
        url: String,
    ) {
        lastLoadedUrl = url
        prepareWebview()

        this.webViewClient =
            object : WebViewClient() {
            }

        loadUrl(url)
        showAsBottomSheet(activity)
    }

    private fun showAsBottomSheet(activity: Activity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val radius = 24.0f * Resources.getSystem().displayMetrics.density // Convert dp to px

        bottomSheetContainer =
            CoordinatorLayout(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                // Add dimmed background - clicking outside dismisses
                setBackgroundColor("#80000000".toColorInt()) // 50% black overlay
                setOnClickListener {
                    hideBottomSheet()
                }
            }

        bottomSheetFrame =
            FrameLayout(context).apply {
                id = R.id.container
                layoutParams =
                    CoordinatorLayout
                        .LayoutParams(
                            CoordinatorLayout.LayoutParams.MATCH_PARENT,
                            CoordinatorLayout.LayoutParams.MATCH_PARENT,
                        ).apply {
                            behavior = BottomSheetBehavior<FrameLayout>()
                        }

                // Apply rounded background and clipping to the container
                background = createRoundedBackground(radius)
                clipToOutline = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 8f
                }

                // Prevent clicks from propagating to parent (dimmed background)
                setOnClickListener { /* consume click */ }
            }

        // Remove this webview from any existing parent
        (parent as? ViewGroup)?.removeView(this)

        // Remove any background from the webview itself
        setBackgroundColor(Color.TRANSPARENT)

        // Add webview to the bottom sheet frame
        bottomSheetFrame?.addView(
            this,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        bottomSheetContainer?.addView(bottomSheetFrame)
        rootView.addView(bottomSheetContainer)

        setupBottomSheetBehavior(activity)
    }

    private fun setupBottomSheetBehavior(activity: Activity) {
        bottomSheetFrame?.let { frame ->
            bottomSheetBehavior = BottomSheetBehavior.from(frame)

            val displayMetrics = DisplayMetrics()
            activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
            val screenHeight = displayMetrics.heightPixels

            // Set initial height to 60% of screen
            val initialHeight = (screenHeight * 0.6f).toInt()
            bottomSheetBehavior?.apply {
                peekHeight = initialHeight
                state = BottomSheetBehavior.STATE_COLLAPSED
                isHideable = true
                isDraggable = true

                addBottomSheetCallback(
                    object : BottomSheetCallback() {
                        override fun onStateChanged(
                            bottomSheet: View,
                            newState: Int,
                        ) {
                            when (newState) {
                                BottomSheetBehavior.STATE_HIDDEN -> {
                                    dismissBottomSheet()
                                    onDismiss?.invoke()
                                }
                            }
                        }

                        override fun onSlide(
                            bottomSheet: View,
                            slideOffset: Float,
                        ) {
                            // Optional: Handle slide animations
                        }
                    },
                )
            }
        }
    }

    fun dismissBottomSheet() {
        bottomSheetContainer?.let { container ->
            val rootView = container.parent as? ViewGroup
            rootView?.removeView(container)
        }
        bottomSheetContainer = null
        bottomSheetFrame = null
        bottomSheetBehavior = null
    }

    fun expandBottomSheet() {
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun collapseBottomSheet() {
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    fun hideBottomSheet() {
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun createRoundedBackground(radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadii =
                floatArrayOf(
                    radius,
                    radius, // top-left
                    radius,
                    radius, // top-right
                    0f,
                    0f, // bottom-right
                    0f,
                    0f, // bottom-left
                )
        }

    interface OnScrollChangeListener {
        fun onScrollChanged(
            currentHorizontalScroll: Int,
            currentVerticalScroll: Int,
            oldHorizontalScroll: Int,
            oldcurrentVerticalScroll: Int,
        )
    }
}
