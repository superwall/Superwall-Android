package com.superwall.sdk.view

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.webkit.WebView
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.core.widget.PopupWindowCompat
import com.superwall.sdk.misc.runOnUiThread


class PaywallView(webView: WebView) {
    private val webView: WebView = webView
    private var currentActivity: Activity? = null
    private lateinit var popupWindow: PopupWindow

    /**
     * Create a new Android PopupWindow that draws over the current Activity
     *
     * @param parentRelativeLayout root layout to attach to the pop up window
     */
    private fun createPopupWindow(parentRelativeLayout: RelativeLayout) {
        popupWindow = PopupWindow(
            parentRelativeLayout,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT ,
            true
        )
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.setTouchable(true)
        // NOTE: This is required for getting fullscreen under notches working in portrait mode
        popupWindow.setClippingEnabled(false)
        var gravity = 0

        // Using panel for fullbleed IAMs and dialog for non-fullbleed. The attached dialog type
        // does not allow content to bleed under notches but panel does.
        val displayType =
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        PopupWindowCompat.setWindowLayoutType(
            popupWindow,
            displayType
        )
        popupWindow.showAtLocation(
            currentActivity!!.window.decorView.rootView,
            gravity,
            0,
            0
        )
    }

    public fun showView(activity: Activity) {
        _showView(activity)
//        // Do not add view until activity is ready
//        private void delayShowUntilAvailable(final Activity currentActivity) {
//            if (OSViewUtils.isActivityFullyReady(currentActivity) && parentRelativeLayout == null) {
//                showInAppMessageView(currentActivity);
//                return;
//            }
//            new Handler().postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    delayShowUntilAvailable(currentActivity);
//                }
//            }, ACTIVITY_INIT_DELAY);
//        }
    }

    public fun dismiss() {
        // Run on main thread
        runOnUiThread {
            // Stuff that updates the UI
            popupWindow.dismiss()
        }
    }

    private fun _showView(activity: Activity) {
        /* IMPORTANT
        * The only place where currentActivity should be assigned to InAppMessageView */
        this.currentActivity = activity
        val context = activity.applicationContext

        setUpParentRelativeLayout(context)
        parentRelativeLayout.addView(webView)
        createPopupWindow(parentRelativeLayout)

//        create

        // OSUtils.runOnMainUIThread(new Runnable() {
        //     @Override
        //     public void run() {
        //         if (webView == null)
        //             return;

        //         webView.setLayoutParams(relativeLayoutParams);

        //         Context context = currentActivity.getApplicationContext();
        //         setUpDraggableLayout(context, draggableRelativeLayoutParams, webViewLayoutParams);
        //         setUpParentRelativeLayout(context);
        //         createPopupWindow(parentRelativeLayout);

        //         if (messageController != null) {
        //             animateInAppMessage(displayLocation, draggableRelativeLayout, parentRelativeLayout);
        //         }

        //         startDismissTimerIfNeeded();
        //     }
        // });
            
    }

    private lateinit var parentRelativeLayout: RelativeLayout

    private fun setUpParentRelativeLayout(context: Context) {
        parentRelativeLayout = RelativeLayout(context)
        parentRelativeLayout.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        parentRelativeLayout.setClipChildren(false)
        parentRelativeLayout.setClipToPadding(false)
//        parentRelativeLayout.addView(draggableRelativeLayout)
    }

}