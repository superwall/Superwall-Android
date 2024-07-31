package com.superwall.sdk.paywall.vc

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.SuperwallPaywallActivity.NotificationPermissionCallback
import com.superwall.sdk.store.transactions.notifications.NotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SuperwallPaywallFragment : Fragment() {
    companion object {
        private const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1001
        const val NOTIFICATION_CHANNEL_ID = "com.superwall.android.notifications"
        private const val NOTIFICATION_CHANNEL_NAME = "Trial Reminder Notifications"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION =
            "Notifications sent when a free trial is about to end."
        private const val VIEW_KEY = "viewKey"
        private const val PRESENTATION_STYLE_KEY = "presentationStyleKey"
        private const val IS_LIGHT_BACKGROUND_KEY = "isLightBackgroundKey"

        fun newInstance(
            isBackgroundLight: Boolean,
            key: String = UUID.randomUUID().toString(),
            presentationStyleOverride: PaywallPresentationStyle? = null,
        ): SuperwallPaywallFragment =
            SuperwallPaywallFragment().apply {
                retainInstance = true
                arguments =
                    Bundle().apply {
                        putString(VIEW_KEY, key)
                        putSerializable(PRESENTATION_STYLE_KEY, presentationStyleOverride)
                        putBoolean(
                            IS_LIGHT_BACKGROUND_KEY,
                            isBackgroundLight, // view.paywall.backgroundColor.isLightColor()
                        )
                    }
            }
    }

    val paywallView: WeakReference<PaywallView>
        get() = WeakReference((view as? PaywallView?))

    fun onBackPressed() {
        paywallView?.get()?.dismiss(
            result = PaywallResult.Declined(),
            closeReason = PaywallCloseReason.ManualClose,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val key =
            arguments?.getString(VIEW_KEY) ?: error("No key in bundle - cannot display paywall")
        val presentationStyleOverride =
            arguments?.getSerializable(PRESENTATION_STYLE_KEY) as? PaywallPresentationStyle
        val isLightBackground = arguments?.getBoolean(IS_LIGHT_BACKGROUND_KEY) ?: false
        return Superwall.instance.viewStore().retrieveView(key).let {
            container?.addView(it)
            container!!
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (activity is OnBackPressedDispatcherOwner) {
            (activity as OnBackPressedDispatcherOwner)
                .onBackPressedDispatcher
                .addCallback(
                    this,
                    object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            onBackPressed()
                        }
                    },
                )
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        Log.e("PWFrag", "Attaching with $view vs ${this.view}")
        Log.e("PWFrag", "Reattaching with ${(paywallView.get()?.childCount ?: "No ${paywallView.get()}")} kids")
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        (requireView() as ViewGroup)?.removeAllViews()
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        if (paywallView?.get()?.isBrowserViewPresented == true) {
            paywallView?.get()?.isBrowserViewPresented = false
        }

        paywallView?.get()?.beforeViewCreated()
    }

    override fun onResume() {
        super.onResume()
        val paywallVc = paywallView ?: return

        paywallVc.get()?.onViewCreated()
    }

    override fun onPause() {
        super.onPause()

        val paywallVc = paywallView.get() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            paywallVc?.beforeOnDestroy()
        }
    }

    override fun onStop() {
        super.onStop()

        val paywallVc = paywallView.get() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            paywallVc?.destroyed()
        }
    }

    private var notificationPermissionCallback: NotificationPermissionCallback? = null

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

        checkAndRequestNotificationPermissions(requireContext(), notificationPermissionCallback!!)
    }

    private fun createNotificationChannel() {
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel =
            NotificationChannel(
                SuperwallPaywallFragment.NOTIFICATION_CHANNEL_ID,
                SuperwallPaywallFragment.NOTIFICATION_CHANNEL_NAME,
                importance,
            ).apply {
                description = SuperwallPaywallFragment.NOTIFICATION_CHANNEL_DESCRIPTION
            }
        channel.setShowBadge(false)
        // Register the channel with the system
        val notificationManager: NotificationManager =
            requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                        SuperwallPaywallFragment.REQUEST_CODE_NOTIFICATION_PERMISSION,
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
            requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            notificationManager.getNotificationChannel(SuperwallPaywallFragment.NOTIFICATION_CHANNEL_ID)
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
        if (requestCode == SuperwallPaywallFragment.REQUEST_CODE_NOTIFICATION_PERMISSION &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val isGranted =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            // Invoke the callback here
            notificationPermissionCallback?.onPermissionResult(isGranted)
        }
    }
}
