package com.superwall.sdk.paywall.vc

import android.Manifest
import android.R
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.misc.isLightColor
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.store.transactions.notifications.NotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SuperwallPaywallActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1001
        const val NOTIFICATION_CHANNEL_ID = "com.superwall.android.notifications"
        private const val NOTIFICATION_CHANNEL_NAME = "Trial Reminder Notifications"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION =
            "Notifications sent when a free trial is about to end."
        private const val VIEW_KEY = "viewKey"
        private const val PRESENTATION_STYLE_KEY = "presentationStyleKey"
        private const val IS_LIGHT_BACKGROUND_KEY = "isLightBackgroundKey"

        fun startWithView(
            context: Context,
            view: PaywallView,
            key: String = UUID.randomUUID().toString(),
            presentationStyleOverride: PaywallPresentationStyle? = null,
        ) {
            // We force this in main scope in case the user started it from a non-main thread
            CoroutineScope(Dispatchers.Main).launch {
                if (view.webView.parent == null) {
                    view.addView(view.webView)
                }
                val viewStorageViewModel = Superwall.instance.viewStore()
                // If we started it directly and the view does not have shimmer and loading attached
                // We set them up for this PaywallView
                if (view.children.none { it is LoadingView || it is ShimmerView }) {
                    (viewStorageViewModel.retrieveView(LoadingView.TAG) as LoadingView)
                        .let { view.setupLoading(it) }
                    (viewStorageViewModel.retrieveView(ShimmerView.TAG) as ShimmerView)
                        .let { view.setupShimmer(it) }
                    view.layoutSubviews()
                }
                val intent =
                    Intent(context, SuperwallPaywallActivity::class.java).apply {
                        putExtra(VIEW_KEY, key)
                        putExtra(PRESENTATION_STYLE_KEY, presentationStyleOverride)
                        putExtra(
                            IS_LIGHT_BACKGROUND_KEY,
                            view.paywall.backgroundColor.isLightColor(),
                        )
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                viewStorageViewModel.storeView(key, view)
                context.startActivity(intent)
            }
        }
    }

    private var contentView: View? = null
    private var notificationPermissionCallback: NotificationPermissionCallback? = null
    private val isBottomSheetView
        get() = contentView is CoordinatorLayout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun setContentView(view: View) {
        super.setContentView(view)
        contentView = view
    }

    private val mainScope = CoroutineScope(Dispatchers.Main)

    private fun paywallView(): PaywallView? {
        val content = contentView ?: error("Content view is null")
        if (content is PaywallView) {
            return content
        } else if (content is CoordinatorLayout) {
            return (content.getChildAt(0) as FrameLayout).getChildAt(0) as PaywallView
        } else {
            return null
        }
    }

    private fun setupBottomSheetLayout(paywallView: PaywallView) {
        val activityView =
            layoutInflater.inflate(com.superwall.sdk.R.layout.activity_bottom_sheet, null)
        setContentView(activityView)
        initBottomSheetBehavior()
        val container =
            activityView.findViewById<FrameLayout>(com.superwall.sdk.R.id.container)
        container.setOnClickListener { finish() }
        container.addView(paywallView)
        container.requestLayout()
    }

    private fun initBottomSheetBehavior() {
        var bottomSheetBehavior = BottomSheetBehavior.from((contentView as ViewGroup).getChildAt(0))
        bottomSheetBehavior.halfExpandedRatio = 0.62f
        // Expanded by default
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        bottomSheetBehavior.skipCollapsed = true
        bottomSheetBehavior.addBottomSheetCallback(
            object :
                BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    bottomSheet: View,
                    newState: Int,
                ) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        finish()
                    }
                }

                override fun onSlide(
                    bottomSheet: View,
                    slideOffset: Float,
                ) {
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        val presentationStyle =
            intent.getSerializableExtra(PRESENTATION_STYLE_KEY) as? PaywallPresentationStyle

        // Show content behind the status bar
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = Color.TRANSPARENT
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isLightBackground = intent.getBooleanExtra(IS_LIGHT_BACKGROUND_KEY, false)
            if (isLightBackground) {
                window.insetsController?.let {
                    it.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    )
                }
            }
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val key = intent.getStringExtra(VIEW_KEY)
        if (key == null) {
            finish() // Close the activity if there's no key
            return
        }

        val viewStorageViewModel = Superwall.instance.viewStore()

        val view =
            viewStorageViewModel.retrieveView(key) as? PaywallView ?: run {
                finish() // Close the activity if the view associated with the key is not found
                return
            }

        val isBottomSheetStyle = presentationStyle == PaywallPresentationStyle.DRAWER

        (view.parent as? ViewGroup)?.removeView(view)
        view.encapsulatingActivity = WeakReference(this)
        // If it's a bottom sheet, we set activity as transparent and show the UI in a bottom sheet container
        if (isBottomSheetStyle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setupBottomSheetLayout(view)
        } else {
            setContentView(view)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    view.dismiss(
                        result = PaywallResult.Declined(),
                        closeReason = PaywallCloseReason.ManualClose,
                    )
                }
            },
        )

        try {
            supportActionBar?.hide()
        } catch (e: Throwable) {
        }

        // TODO: handle animation and style from `presentationStyleOverride`
        when (intent.getSerializableExtra(PRESENTATION_STYLE_KEY) as? PaywallPresentationStyle) {
            PaywallPresentationStyle.PUSH -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(
                        OVERRIDE_TRANSITION_OPEN,
                        R.anim.slide_in_left,
                        R.anim.slide_in_left,
                    )
                } else {
                    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_in_left)
                }
            }

            PaywallPresentationStyle.FULLSCREEN -> {
                enableEdgeToEdge()
            }

            PaywallPresentationStyle.FULLSCREEN_NO_ANIMATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                } else {
                    overridePendingTransition(0, 0)
                }
                enableEdgeToEdge()
            }

            PaywallPresentationStyle.MODAL -> {
                // TODO: Not yet supported in Android
            }
            PaywallPresentationStyle.NONE,
            PaywallPresentationStyle.DRAWER,
            null,
            -> {
                // Do nothing
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val paywallVc = paywallView() ?: return

        if (paywallVc.isBrowserViewPresented) {
            paywallVc.isBrowserViewPresented = false
        }

        paywallVc.beforeViewCreated()
    }

    private fun setBottomSheetTransparency() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setTranslucent(true)
            val colorFrom = Color.argb(0, 0, 0, 0)
            val colorTo = Color.argb(200, 0, 0, 0)
            with(ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)) {
                setDuration(600) // milliseconds
                addUpdateListener { animator -> window.setBackgroundDrawable(ColorDrawable(animator.animatedValue as Int)) }
                start()
            }
        } else {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        }
    }

    private fun hideBottomSheetAndFinish() {
        val colorFrom = Color.argb(200, 0, 0, 0)
        val colorTo = Color.argb(0, 0, 0, 0)

        // First animate the background dim, then call finish on the view
        with(ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)) {
            setDuration(300) // milliseconds
            addUpdateListener { animator ->
                val e = ((animator.animatedValue as Int) / colorFrom)
                Log.e("Percentage", " -------------------------- $e -------------")
                if (e < 0.1) {
                    super.finish()
                }
                window.setBackgroundDrawable(ColorDrawable(animator.animatedValue as Int))
            }
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        val paywallVc = paywallView() ?: return
        if (isBottomSheetView) {
            setBottomSheetTransparency()
        }
        paywallVc.onViewCreated()
    }

    override fun onPause() {
        super.onPause()

        val paywallVc = paywallView() ?: return
        mainScope.launch {
            paywallVc.beforeOnDestroy()
        }
    }

    override fun onStop() {
        super.onStop()

        val paywallVc = paywallView() ?: return

        mainScope.launch {
            paywallVc.destroyed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (contentView?.parent as? ViewGroup)?.removeView(contentView)
        // Clear reference to activity in the view
        (paywallView() as? ActivityEncapsulatable)?.encapsulatingActivity = null

        // Clear the reference to the contentView
        contentView = null
    }

    //region Notifications
    interface NotificationPermissionCallback {
        fun onPermissionResult(granted: Boolean)
    }

    override fun finish() {
        if (isBottomSheetView) {
            mainScope.launch {
                hideBottomSheetAndFinish()
            }
        } else {
            super.finish()
        }
    }

    suspend fun attemptToScheduleNotifications(
        notifications: List<LocalNotification>,
        factory: DeviceHelperFactory,
        context: Context,
    ) = suspendCoroutine { continuation ->
        if (notifications.isEmpty()) {
            continuation.resume(Unit) // Resume immediately as there's nothing to schedule
            return@suspendCoroutine
        }

        createNotificationChannel()

        notificationPermissionCallback =
            object : NotificationPermissionCallback {
                override fun onPermissionResult(granted: Boolean) {
                    if (granted) {
                        NotificationScheduler.scheduleNotifications(
                            notifications = notifications,
                            factory = factory,
                            context = context,
                        )
                    }
                    continuation.resume(Unit) // Resume coroutine after processing
                }
            }

        checkAndRequestNotificationPermissions(this, notificationPermissionCallback!!)
    }

    private fun createNotificationChannel() {
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                importance,
            ).apply {
                description = NOTIFICATION_CHANNEL_DESCRIPTION
            }
        channel.setShowBadge(false)
        // Register the channel with the system
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun checkAndRequestNotificationPermissions(
        context: Context,
        callback: NotificationPermissionCallback,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        context as Activity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                ) {
                    // First time asking or user previously denied without 'Don't ask again'
                    ActivityCompat.requestPermissions(
                        context,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_CODE_NOTIFICATION_PERMISSION,
                    )
                } else {
                    // Permission previously denied with 'Don't ask again'
                    callback.onPermissionResult(false)
                }
            } else {
                callback.onPermissionResult(true)
            }
        } else {
            callback.onPermissionResult(areNotificationsEnabled(context))
        }
    }

    private fun areNotificationsEnabled(context: Context): Boolean {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATION_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            // Invoke the callback here
            notificationPermissionCallback?.onPermissionResult(isGranted)
        }
    }
    //endregion
}
