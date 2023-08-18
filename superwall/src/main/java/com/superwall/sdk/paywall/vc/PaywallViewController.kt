package com.superwall.sdk.paywall.vc

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.RelativeLayout
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.*

class PaywallViewController(
    override var paywall: Paywall,
    val eventDelegate: PaywallViewControllerEventDelegate? = null,
    var delegate: PaywallViewControllerDelegate? = null,
    val deviceHelper: DeviceHelper,
    val factory: TriggerSessionManagerFactory,
    val storage: Storage,
    val paywallManager: PaywallManager,
    override val webView: SWWebView,
    val cache: PaywallViewControllerCache?
    ) : PaywallMessageHandlerDelegate, SWWebViewDelegate {


    /// A loading spinner that appears when making a purchase.
    private var loadingViewController: LoadingViewController? = null

    /// A shimmer view that appears when loading the webpage.
    private var shimmerView: ShimmerView? = null

    override var loadingState: PaywallLoadingState = PaywallLoadingState.Unknown()
        set(value) {
            val oldValue = field
            field = value
            if (value != oldValue)  {
                loadingStateDidChange(oldValue)
            }
        }
    override val isActive: Boolean
        get() = isPresented || isBeingPresented

    /// Defines whether the view controller is being presented or not.
    private var isPresented = false
    private var isBeingPresented = false
    override fun eventDidOccur(paywallEvent: PaywallWebEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            eventDelegate?.eventDidOccur(paywallEvent, this@PaywallViewController)
        }
    }

    val info: PaywallInfo get() = paywall.getInfo(request?.presentationInfo?.eventData, factory)

    override fun presentSafariInApp(url: String) {
        // TODO: Implement this
//        val context = this.context // Or replace with appropriate context if not inside an activity/fragment
//        if (!context.packageManager.canOpenApplication(url)) {
//            Logger.debug(
//                LogLevel.WARN,
//                Logger.Scope.PAYWALL_VIEW_CONTROLLER,
//                "Invalid URL provided for \"Open URL\" click behavior."
//            )
//            return
//        }
//
//        val safariIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
//        this.isSafariIntentPresented = true
//        context.startActivity(safariIntent)
    }

    override fun presentSafariExternal(url: String) {
        // TODO: Implement this
//        val context = this.context // Or replace with appropriate context if not inside an activity/fragment
//        val safariIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
//        context.startActivity(safariIntent)
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


//    init {
//        runOnUiThread {
////            webView.loadUrl(paywall.url.toString())
//        }
//    }

    fun viewDidLoad(activity: Activity) {
        runOnUiThread {
            setUpParentRelativeLayout(activity.applicationContext)
            parentRelativeLayout.addView(webView)
            createPopupWindow(activity, parentRelativeLayout)

            loadWebView()
        }
    }

    val cacheKey = PaywallCacheLogic.key(
        identifier = paywall.identifier,
        locale = deviceHelper.locale
    )

    override  val paywallInfo: PaywallInfo
        get() = paywall.getInfo(fromEvent = request?.presentationInfo?.eventData, factory = factory)


    override  var request: PresentationRequest? = null

    var paywallStatePublisher: PaywallStatePublisher? = null

    var currentActivity: Activity? = null
        set(value) {
            field = value

            // Execute your code here when context is set
            if (value != null) {
                viewDidLoad(value)
            }
        }

    // TODO: Implement this function for real
    fun dismiss(result: PaywallResult, closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic, completion: (() -> Unit)? = null) {
        val dismissCompletionBlock = completion
        val paywallResult = result
        paywall.closeReason = closeReason


        // TODO: Implement a way to dismiss the paywall via the delegate Implement a way to dismiss the paywall via the delegate Implement a way to dismiss the paywall via the delegate Implement a way to dismiss the paywall via the delegate
        // SW-2161 https://linear.app/superwall/issue/SW-2161/%5Bandroid%5D-%5Bv0%5D-ensure-dismissing-when-using-getpaywallviewcontroller

        dismiss(false)
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

        this.request = request
        this.paywallStatePublisher = paywallStatePublisher
        this.currentActivity = presenter





//        val intent = Intent(presenter, this::class.java)
//        presenter.startActivity(intent) // Assuming `this` is an Activity

        println("!!! Presenting!!!")

        /* IMPORTANT
      * The only place where currentActivity should be assigned to InAppMessageView */
//        this.currentActivity = activity

//        runOnUiThread {
//
//            val context = presenter.applicationContext
//            setUpParentRelativeLayout(context)
//            parentRelativeLayout.addView(webView)
//            createPopupWindow(presenter, parentRelativeLayout)
//        }


        completion(true)
    }

    // Loading state

    fun loadingStateDidChange(from: PaywallLoadingState) {
        when(loadingState) {
            is PaywallLoadingState.Unknown -> {
            }
            is PaywallLoadingState.LoadingPurchase, is PaywallLoadingState.ManualLoading -> {
                // Add Loading View
                addLoadingView()
            }
            is PaywallLoadingState.LoadingURL -> {
                addShimmerView()
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

    fun addLoadingView() {
        val currentContext = currentActivity?.applicationContext ?: run {
            println("currentContext is null")
            return
        }

        val view = LoadingViewController(currentContext)
        parentRelativeLayout.addView(view)

        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = layoutParams

        this.loadingViewController = view
    }

    fun addShimmerView() {
        val currentContext = currentActivity?.applicationContext ?: run {
            println("currentContext is null")
            return
        }

        val view = ShimmerView(currentContext)
        parentRelativeLayout.addView(view)

        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = layoutParams

        this.shimmerView = view
    }

    fun hideLoadingView() {
        parentRelativeLayout.removeView(this.loadingViewController)
    }

    fun hideShimmerView() {
        parentRelativeLayout.removeView(this.shimmerView)
    }

    fun showRefreshButtonAfterTimeout(isVisible: Boolean) {
        // TODO: Implement this
    }


    // Janky presentation stuff

    private lateinit var popupWindow: PopupWindow

    /**
     * Create a new Android PopupWindow that draws over the current Activity
     *
     * @param parentRelativeLayout root layout to attach to the pop up window
     */
    private fun createPopupWindow(presenter: Activity, parentRelativeLayout: RelativeLayout) {
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
//        val displayType =
//            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        val displayType = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        PopupWindowCompat.setWindowLayoutType(
            popupWindow,
            displayType
        )
        print("!!! Showing popup window ${presenter.window.decorView.rootView}")
        popupWindow.showAtLocation(
            presenter.window.decorView.rootView,
            gravity,
            0,
            0
        )
    }


    private lateinit var parentRelativeLayout: RelativeLayout

    private fun setUpParentRelativeLayout(context: Context) {
        parentRelativeLayout = RelativeLayout(context)
        parentRelativeLayout.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        parentRelativeLayout.setClipChildren(false)
        parentRelativeLayout.setClipToPadding(false)
    }


    // This is basically the same as `dismiss(animated: Bool)`
    // in the original iOS implementation
    private fun dismiss(presentationIsAnimated: Boolean) {
        // TODO: SW-2162 Implement animation support
        // https://linear.app/superwall/issue/SW-2162/%5Bandroid%5D-%5Bv1%5D-get-animated-presentation-working
        popupWindow.dismiss()
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


}