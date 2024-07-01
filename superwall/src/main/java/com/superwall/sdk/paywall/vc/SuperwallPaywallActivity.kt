package com.superwall.sdk.paywall.vc

import android.Manifest
import android.R
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SuperwallPaywallActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1001
        const val NOTIFICATION_CHANNEL_ID = "com.superwall.android.notifications"
        private const val NOTIFICATION_CHANNEL_NAME = "Trial Reminder Notifications"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION = "Notifications sent when a free trial is about to end."
        private const val VIEW_KEY = "viewKey"
        private const val PRESENTATION_STYLE_KEY = "presentationStyleKey"
        private const val IS_LIGHT_BACKGROUND_KEY = "isLightBackgroundKey"

        fun startWithView(
            context: Context,
            view: PaywallViewController,
            key: String = UUID.randomUUID().toString(),
            presentationStyleOverride: PaywallPresentationStyle? = null,
        ) {
            val viewStorageViewModel = Superwall.instance.viewStore()
            viewStorageViewModel.storeView(key, view)

            val intent =
                Intent(context, SuperwallPaywallActivity::class.java).apply {
                    putExtra(VIEW_KEY, key)
                    putExtra(PRESENTATION_STYLE_KEY, presentationStyleOverride)
                    putExtra(IS_LIGHT_BACKGROUND_KEY, view.paywall.backgroundColor.isLightColor())
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }

            context.startActivity(intent)
        }
    }

    private var contentView: View? = null
    private var notificationPermissionCallback: NotificationPermissionCallback? = null

    override fun setContentView(view: View) {
        super.setContentView(view)
        contentView = view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

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
            viewStorageViewModel.retrieveView(key) as? PaywallViewController ?: run {
                finish() // Close the activity if the view associated with the key is not found
                return
            }

        (view.parent as? ViewGroup)?.removeView(view)
        view.encapsulatingActivity = this

        setContentView(view)

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
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_in_left)
            }
            PaywallPresentationStyle.DRAWER -> {
            }
            PaywallPresentationStyle.FULLSCREEN -> {
            }
            PaywallPresentationStyle.FULLSCREEN_NO_ANIMATION -> {
            }
            PaywallPresentationStyle.MODAL -> {
            }
            PaywallPresentationStyle.NONE -> {
                // Do nothing
            }
            null -> {
                // Do nothing
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val paywallVc = contentView as? PaywallViewController ?: return

        if (paywallVc.isSafariVCPresented) {
            paywallVc.isSafariVCPresented = false
        }

        paywallVc.viewWillAppear()
    }

    override fun onResume() {
        super.onResume()
        val paywallVc = contentView as? PaywallViewController ?: return

        paywallVc.viewDidAppear()
    }

    override fun onPause() {
        super.onPause()

        val paywallVc = contentView as? PaywallViewController ?: return

        CoroutineScope(Dispatchers.Main).launch {
            paywallVc.viewWillDisappear()
        }
    }

    override fun onStop() {
        super.onStop()

        val paywallVc = contentView as? PaywallViewController ?: return

        CoroutineScope(Dispatchers.Main).launch {
            paywallVc.viewDidDisappear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (contentView?.parent as? ViewGroup)?.removeView(contentView)
        // Clear reference to activity in the view
        (contentView as? ActivityEncapsulatable)?.encapsulatingActivity = null

        // Clear the reference to the contentView
        contentView = null
    }

    //region Notifications
    interface NotificationPermissionCallback {
        fun onPermissionResult(granted: Boolean)
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
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(context as Activity, Manifest.permission.POST_NOTIFICATIONS)) {
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
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            val isGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            // Invoke the callback here
            notificationPermissionCallback?.onPermissionResult(isGranted)
        }
    }
    //endregion
}
