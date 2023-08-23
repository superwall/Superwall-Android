package com.superwall.sdk.paywall.vc

import Logger
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.opengl.Visibility
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat.startActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.PopupWindowCompat
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.trigger_session.LoadState
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.misc.runOnUiThread
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallCacheLogic
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.manager.PaywallViewControllerCache
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PaywallStatePublisher
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerEventDelegate
import com.superwall.sdk.paywall.vc.web_view.SWWebView
import com.superwall.sdk.paywall.vc.web_view.SWWebViewDelegate
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandlerDelegate
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.view.fatalAssert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.lang.ref.WeakReference
import java.net.MalformedURLException
import java.net.URL
import java.util.*

class PaywallViewController(
    context: Context,
    override var paywall: Paywall,
    val eventDelegate: PaywallViewControllerEventDelegate? = null,
    var delegate: PaywallViewControllerDelegate? = null,
    val deviceHelper: DeviceHelper,
    val factory: TriggerSessionManagerFactory,
    val storage: Storage,
    val paywallManager: PaywallManager,
    override val webView: SWWebView,
    val cache: PaywallViewControllerCache?,
    private val shimmerView: ShimmerView = ShimmerView(context),
    private val loadingViewController: LoadingViewController = LoadingViewController(context)
) : FrameLayout(context), PaywallMessageHandlerDelegate, SWWebViewDelegate {

    //region Public properties

    // MUST be set prior to presentation
    override var request: PresentationRequest? = null

    //endregion

    //region Public functions

    fun present(
        presenter: Activity,
        request: PresentationRequest,
        presentationStyleOverride: PaywallPresentationStyle?,
        paywallStatePublisher: MutableStateFlow<PaywallState>,
        completion: (Boolean) -> Unit
    ) {
        if (Superwall.instance.isPaywallPresented
        // TODO: Presentation santization
//            || presenter is PaywallActivity
//            || presenter.isTaskRoot
        ) {  // Not an exact equivalent of `isBeingPresented`
            return completion(false)
        }

        // TODO: do something with these values
        this.presentationRequest = request
        this.paywallStatePublisher = paywallStatePublisher

        // TODO: handle animation and style from `presentationStyleOverride`
        this.popupWindow = PopupWindowUtility.presentViewControllerInPopupWindow(presenter, this)

        completion(true)
    }

    // TODO: Implement this function for real
    fun dismiss(result: PaywallResult, closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic, completion: (() -> Unit)? = null) {
        paywall?.closeReason = closeReason

        // TODO: Implement a way to dismiss the paywall via the delegate Implement a way to dismiss the paywall via the delegate Implement a way to dismiss the paywall via the delegate Implement a way to dismiss the paywall via the delegate
        // Sw-2161 https://linear.app/superwall/issue/SW-2161/%5Bandroid%5D-%5Bv0%5D-ensure-dismissing-when-using-getpaywallviewcontroller

        dismiss(false)
    }



    //endregion

    //region Initialization

    init {
        // Add the webView
        addView(webView)

        // Add the shimmer view and hide it
        addView(shimmerView)
        hideShimmerView()

        // Add the loading view and hide it
        addView(loadingViewController)
        hideLoadingView()

        // Listen for layout changes
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            layoutSubviews()
        }
        viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    //endregion

    //region Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Assert if no `presentationRequest`
        fatalAssert(presentationRequest != null, "Must be presenting a PaywallViewController with a `presentationRequest` instance.")

        loadWebView()
    }

    override fun eventDidOccur(paywallEvent: PaywallWebEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            eventDelegate?.eventDidOccur(paywallEvent, this@PaywallViewController)
        }
    }

    //endregion

    //region Layout

    fun layoutSubviews() {
        webView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        shimmerView.layoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    //endregion

    //region Presentation

    private var presentationRequest: PresentationRequest? = null

    private var paywallStatePublisher: PaywallStatePublisher? = null

    // The active popupWindow instance if this view controller has been presented in one.
    private var popupWindow: PopupWindow? = null

    // This is basically the same as `dismiss(animated: Bool)`
    // in the original iOS implementation
    private fun dismiss(presentationIsAnimated: Boolean) {
        // TODO: SW-2162 Implement animation support
        // https://linear.app/superwall/issue/SW-2162/%5Bandroid%5D-%5Bv1%5D-get-animated-presentation-working
        popupWindow?.dismiss()

    }

    private fun showLoadingView() {
        loadingViewController.visibility = View.VISIBLE
    }

    private fun hideLoadingView() {
        loadingViewController.visibility = View.GONE
    }

    private fun showShimmerView() {
        shimmerView.visibility = View.VISIBLE
        // TODO: Start shimmer animation if needed
    }

    private fun hideShimmerView() {
        shimmerView.visibility = View.GONE
        // TODO: Stop shimmer animation if needed
    }

    fun showRefreshButtonAfterTimeout(isVisible: Boolean) {
        // TODO: Implement this
    }

    fun presentAlert(
        title: String? = null,
        message: String? = null,
        actionTitle: String? = null,
        closeActionTitle: String = "Done",
        action: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null
    ) {
        // SW-2211
        // https://linear.app/superwall/issue/SW-2211/[android]-[v0]-add-support-for-the-alert-view-controller-on-the

        // Print out all the properties one by one
        println("title: $title")
        println("message: $message")
        println("actionTitle: $actionTitle")
        println("closeActionTitle: $closeActionTitle")
        println("action: $action")
        println("onClose: $onClose")

        // Wait for 3 seconds, then call onClose
        Handler(Looper.getMainLooper()).postDelayed({
            onClose?.invoke()
        }, 3000)


//        if (presentedViewController != null) {
//            return
//        }
//
//        val alertController = AlertControllerFactory.make(
//            title = title,
//            message = message,
//            actionTitle = actionTitle,
//            closeActionTitle = closeActionTitle,
//            action = action,
//            onClose = onClose,
//            sourceView = this.view
//        )
//
//        present(alertController, animated = true) {
//            if (loadingState != LoadingState.loadingURL) {
//                loadingState = LoadingState.ready
//            }
//        }
    }

    //endregion

    //region State

    //TODO: Are these the same? `info` and `paywallInfo`?
    val info: PaywallInfo get() = paywall.getInfo(request?.presentationInfo?.eventData, factory)

    override val paywallInfo: PaywallInfo
        get() = paywall.getInfo(fromEvent = request?.presentationInfo?.eventData, factory = factory)

    override var loadingState: PaywallLoadingState = PaywallLoadingState.Unknown()
        set(value) {
            val oldValue = field
            field = value
            if (value != oldValue) {
                loadingStateDidChange(oldValue)
            }
        }
    override val isActive: Boolean
        get() = isPresented || isBeingPresented

    /// Defines whether the view controller is being presented or not.
    private var isPresented = false
    private var isBeingPresented = false

    fun loadingStateDidChange(from: PaywallLoadingState) {
        when (loadingState) {
            is PaywallLoadingState.Unknown -> {
            }
            is PaywallLoadingState.LoadingPurchase, is PaywallLoadingState.ManualLoading -> {
                // Add Loading View
                showLoadingView()
            }
            is PaywallLoadingState.LoadingURL -> {
                showShimmerView()
                showRefreshButtonAfterTimeout(isVisible = true)
                // TODO: Animation
                /*
                 UIView.springAnimate {
                    self.webView.alpha = 0.0
                    self.webView.transform = CGAffineTransform.identity.translatedBy(x: 0, y: -10)
                  }
                 */
            }
            is PaywallLoadingState.Ready -> {
                /*
          let translation = CGAffineTransform.identity.translatedBy(x: 0, y: 10)
          let spinnerDidShow = oldValue == .loadingPurchase || oldValue == .manualLoading
          webView.transform = spinnerDidShow ? .identity : translation
                */
                showRefreshButtonAfterTimeout(false)
                hideLoadingView()
                hideShimmerView()

                /*

                  if !spinnerDidShow {
                    UIView.animate(
                      withDuration: 0.6,
                      delay: 0.25,
                      animations: {
                        self.shimmerView?.alpha = 0.0
                        self.webView.alpha = 1.0
                        self.webView.transform = .identity
                      },
                      completion: { _ in
                        self.shimmerView?.removeFromSuperview()
                        self.shimmerView = nil
                      }
                    )
                         }
                 */

            }
        }
    }

    private fun loadWebView() {
        val url = paywall.url

        if (paywall.webviewLoadingInfo.startAt == null) {
            paywall.webviewLoadingInfo.startAt = Date()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val trackedEvent = InternalSuperwallEvent.PaywallWebviewLoad(
                state = InternalSuperwallEvent.PaywallWebviewLoad.State.Start(),
                paywallInfo = this@PaywallViewController.paywallInfo
            )
            Superwall.instance.track(trackedEvent)

            val triggerSessionManager = factory.getTriggerSessionManager()
            triggerSessionManager.trackWebviewLoad(
                forPaywallId = paywallInfo.databaseId,
                state = LoadState.START
            )
        }

        // TODO: Enable webview caching
//        if (Superwall.instance.options.paywalls.useCachedTemplates) {
//            val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build()
//            webView.loadUrl(request)
//        } else {
        webView.loadUrl(url.toString())
//        }

        loadingState = PaywallLoadingState.LoadingURL()
    }

    //endregion

    //region Deep linking

    override fun presentSafariInApp(url: String) {
        try {
            val parsedUrl = URL(url)
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            customTabsIntent.launchUrl(context, Uri.parse(parsedUrl.toString()))
        } catch (e: MalformedURLException) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallViewController,
                message = "Invalid URL provided for \"Open In-App URL\" click behavior."
            )
        } catch (e: Exception) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallViewController,
                message = "Exception thrown for \"Open In-App URL\" click behavior."
            )
        }
    }

    override fun presentSafariExternal(url: String) {
        try {
            val parsedUrl = URL(url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(parsedUrl.toString()))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: MalformedURLException) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallViewController,
                message = "Invalid URL provided for \"Open External URL\" click behavior."
            )
        } catch (e: Exception) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallViewController,
                message = "Exception thrown for \"Open External URL\" click behavior."
            )
        }
    }

    override fun openDeepLink(url: String) {
        // TODO: Implement this
//        dismiss(
//            result = Result.DECLINED
//        ) {
//            eventDidOccur(PaywallWebEvent.OPENED_DEEP_LINK(url))
//            val context = this.context // Or replace with appropriate context if not inside an activity/fragment
//            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
//            context.startActivity(deepLinkIntent)
//        }
    }

    //endregion

    //region Misc / Unused?

    //    val cacheKey = PaywallCacheLogic.key(
//        identifier = paywall.identifier,
//        locale = deviceHelper.locale
//    )

    //endregion

}

private class PopupWindowUtility() {
    companion object {
        /**
         * Create a new Android PopupWindow that draws over the current Activity
         *
         * @param parentRelativeLayout root layout to attach to the pop up window
         */
        fun presentViewControllerInPopupWindow(activity: Activity, viewController: FrameLayout): PopupWindow {
            val parentRelativeLayout = RelativeLayout(activity.applicationContext)
            parentRelativeLayout.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            parentRelativeLayout.setClipChildren(false)
            parentRelativeLayout.setClipToPadding(false)

            // Check if the viewController already has a parent
            val parentViewGroup = viewController.parent as? ViewGroup
            parentViewGroup?.removeView(viewController)

            // Now add
            parentRelativeLayout.addView(viewController)

            val popupWindow = PopupWindow(
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
//        val displayType =
//            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            val displayType = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            PopupWindowCompat.setWindowLayoutType(
                popupWindow,
                displayType
            )
            print("!!! Showing popup window ${activity.window.decorView.rootView}")
            popupWindow.showAtLocation(
                activity.window.decorView.rootView,
                gravity,
                0,
                0
            )

            return popupWindow
        }
    }
}


//import androidx.compose.runtime.Composable
//import androidx.compose.ui.viewinterop.AndroidView
//import com.yourpackage.PaywallViewController
//
//@Composable
//fun ComposablePaywallViewController() {
//    AndroidView(
//        factory = { context ->
//            PaywallViewController(context)
//        },
//        update = { view ->
//            // Optionally update the view with new data here
//        }
//    )
//}