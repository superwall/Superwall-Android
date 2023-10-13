package com.superwall.sdk.paywall.vc

import LogLevel
import LogScope
import Logger
import android.app.Activity
import android.R
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsIntent
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventObjc
import com.superwall.sdk.analytics.trigger_session.LoadState
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.game.GameControllerDelegate
import com.superwall.sdk.game.GameControllerEvent
import com.superwall.sdk.game.GameControllerManager
import com.superwall.sdk.misc.AlertControllerFactory
import com.superwall.sdk.misc.isDarkColor
import com.superwall.sdk.misc.readableOverlayColor
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallCacheLogic
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.manager.PaywallViewControllerCache
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.get_presentation_result.internallyGetPresentationResult
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.operators.storePresentationObjects
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.result.PresentationResult
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerEventDelegate
import com.superwall.sdk.paywall.vc.web_view.SWWebView
import com.superwall.sdk.paywall.vc.web_view.SWWebViewDelegate
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandlerDelegate
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.view.fatalAssert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.MalformedURLException
import java.net.URL
import java.util.*


class PaywallViewController(
    context: Context,
    override var paywall: Paywall,
    val eventDelegate: PaywallViewControllerEventDelegate? = null,
    var delegate: PaywallViewControllerDelegateAdapter? = null,
    val deviceHelper: DeviceHelper,
    val factory: TriggerSessionManagerFactory,
    val storage: Storage,
    val paywallManager: PaywallManager,
    override val webView: SWWebView,
    val cache: PaywallViewControllerCache?,
    private val loadingViewController: LoadingViewController = LoadingViewController(context)
) : FrameLayout(context), PaywallMessageHandlerDelegate, SWWebViewDelegate, ActivityEncapsulatable, GameControllerDelegate {
    //region Public properties

    // MUST be set prior to presentation
    override var request: PresentationRequest? = null

    //endregion

    //region Presentation properties

    /// The presentation style for the paywall.
    private var presentationStyle: PaywallPresentationStyle

    private val shimmerView: ShimmerView

    var paywallStatePublisher: MutableSharedFlow<PaywallState>? = null

    // The full screen activity instance if this view controller has been presented in one.
    override var encapsulatingActivity: Activity? = null

    /// Stores the ``PaywallResult`` on dismiss of paywall.
    private var paywallResult: PaywallResult? = null

    /// Stores the completion block when calling dismiss.
    private var dismissCompletionBlock: (() -> Unit)? = null

    private var didCallDelegate = false

    private var viewDidAppearCompletion: ((Boolean) -> Unit)? = null

    /// Defines when Safari is presenting in app.
    internal var isSafariVCPresented = false

    //endregion

    // region State properties

    /// The paywall info
    override val info: PaywallInfo
        get() = paywall.getInfo(request?.presentationInfo?.eventData, factory)

    /// The loading state of the paywall.
    override var loadingState: PaywallLoadingState = PaywallLoadingState.Unknown()
        set(value) {
            val oldValue = field
            field = value
            if (value != oldValue) {
                loadingStateDidChange(oldValue)
            }
        }

    /// Determines whether the paywall is presented or not.
    override val isActive: Boolean
        get() = isPresented || isBeingPresented

    /// Defines whether the view controller is being presented or not.
    private var isPresented = false
    private var isBeingPresented = false
    private var presentationWillPrepare = true
    private var presentationDidFinishPrepare = false

    //endregion

    //region Private properties not used in initializer

    /// `true` if there's a survey to complete and the paywall is displayed in a modal style.
    private var didDisableSwipeForSurvey = false

    /// Whether the survey was shown, not shown, or in a holdout. Defaults to not shown.
    // TODO:
//    private var surveyPresentationResult: SurveyPresentationResult = .noShow

    /// If the user match a rule with an occurrence, this needs to be saved on
    /// paywall presentation.
    private var unsavedOccurrence: TriggerRuleOccurrence? = null

    private val cacheKey: String = PaywallCacheLogic.key(paywall.identifier, deviceHelper.locale)

    //endregion

    //region Initialization

    init {
        // Add the webView
        addView(webView)

        val backgroundColor = Color.parseColor(paywall.backgroundColorHex)
        // Add the shimmer view and hide it
        this.shimmerView = ShimmerView(
            context,
            backgroundColor,
            !backgroundColor.isDarkColor(),
            backgroundColor.readableOverlayColor()
        )
        addView(shimmerView)
        hideShimmerView()


        // Add the loading view and hide it
        addView(loadingViewController)
        hideLoadingView()

        setBackgroundColor(backgroundColor)

        // Listen for layout changes
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            layoutSubviews()
        }
        viewTreeObserver.addOnGlobalLayoutListener(listener)
        presentationStyle = paywall.presentation.style
    }

    //endregion

    //region Public functions

    internal fun set(
        request: PresentationRequest,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
        unsavedOccurrence: TriggerRuleOccurrence?
    ) {
        this.request = request
        this.paywallStatePublisher = paywallStatePublisher
        this.unsavedOccurrence = unsavedOccurrence
    }


    fun present(
        presenter: Activity,
        request: PresentationRequest,
        unsavedOccurrence: TriggerRuleOccurrence?,
        presentationStyleOverride: PaywallPresentationStyle?,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
        completion: (Boolean) -> Unit
    ) {
        set(request, paywallStatePublisher, unsavedOccurrence)

        if (presentationStyleOverride != null && presentationStyleOverride != PaywallPresentationStyle.NONE) {
            presentationStyle = presentationStyleOverride
        } else {
            presentationStyle = paywall.presentation.style
        }

        SuperwallPaywallActivity.startWithView(
            presenter.applicationContext,
            this,
            presentationStyleOverride
        )
        viewDidAppearCompletion = completion
    }

    internal fun viewWillAppear() {
        if (isSafariVCPresented) {
            return
        }
        shimmerView.checkForOrientationChanges()
        presentationWillBegin()
    }

    private fun presentationWillBegin() {
        if (!presentationWillPrepare) {
            return
        }
        // TODO: Add surveys
//        if willShowSurvey {
//            didDisableSwipeForSurvey = true
//            presentationController?.delegate = self
//            isModalInPresentation = true
//        }
//        addShimmerView(onPresent: true)

        didCallDelegate = false
        paywall.closeReason = PaywallCloseReason.None

        Superwall.instance.dependencyContainer.delegateAdapter.willPresentPaywall(info)

        presentationWillPrepare = false
    }

    internal suspend fun viewWillDisappear() {
        if (isSafariVCPresented) {
            return
        }
        Superwall.instance.presentationItems.setPaywallInfo(info)
        Superwall.instance.dependencyContainer.delegateAdapter.willDismissPaywall(info)
    }

    internal suspend fun viewDidDisappear() {
        if (isSafariVCPresented) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            trackClose()
        }

        Superwall.instance.dependencyContainer.delegateAdapter.didDismissPaywall(info)

        val result = paywallResult ?: PaywallResult.Declined()

        paywallStatePublisher?.emit(PaywallState.Dismissed(info, result))

        if (!didCallDelegate) {
            delegate?.didFinish(
                paywall = this,
                result = result,
                shouldDismiss = false
            )
        }

        if (paywall.closeReason.stateShouldComplete) {
            paywallStatePublisher = null
        }

         GameControllerManager.shared.clearDelegate(this)

//        if didDisableSwipeForSurvey {
//            presentationController?.delegate = nil
//            isModalInPresentation = false
//            didDisableSwipeForSurvey = false
//        }

        resetPresentationPreparations()

        paywallResult = null
        cache?.activePaywallVcKey = null
        isPresented = false

        dismissCompletionBlock?.invoke()
        dismissCompletionBlock = null
    }

    private fun resetPresentationPreparations() {
        presentationWillPrepare = true
        presentationDidFinishPrepare = false
    }

    internal fun dismiss(
        result: PaywallResult,
        closeReason: PaywallCloseReason = PaywallCloseReason.SystemLogic,
        completion: (() -> Unit)? = null
    ) {
        dismissCompletionBlock = completion
        paywallResult = result
        paywall.closeReason = closeReason

        val isDeclined = paywallResult is PaywallResult.Declined
        val isManualClose = closeReason is PaywallCloseReason.ManualClose

        CoroutineScope(Dispatchers.IO).launch {
            if (isDeclined && isManualClose) {
                val trackedEvent = InternalSuperwallEvent.PaywallDecline(info)

                val presentationResult = Superwall.instance.internallyGetPresentationResult(
                    event = trackedEvent,
                    isImplicit = true
                )
                val paywallPresenterEvent = info.presentedByEventWithName
                val presentedByPaywallDecline = paywallPresenterEvent == SuperwallEventObjc.PaywallDecline.rawName

                Superwall.instance.track(trackedEvent)

                if (presentationResult is PresentationResult.Paywall && !presentedByPaywallDecline) {
                    // If a paywall_decline trigger is active and the current paywall wasn't presented
                    // by paywall_decline, it lands here so as not to dismiss the paywall.
                    // track() will do that before presenting the next paywall.
                    return@launch
                }
            }

            // TODO: Add in survey logic here

            delegate?.let { delegate ->
                didCallDelegate = true
                delegate.didFinish(
                    paywall = this@PaywallViewController,
                    result = result,
                    shouldDismiss = true
                )
            } ?: run {
                // TODO: Implement presentationIsAnimated and pass here:
                dismiss(false)
            }
        }
    }

    //endregion

    //region Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Assert if no `request`
        fatalAssert(request != null, "Must be presenting a PaywallViewController with a `request` instance.")

        loadWebView()
    }

    /// Lets the view controller know that presentation has finished.
    // Only called once per presentation.
    internal fun viewDidAppear() {
        viewDidAppearCompletion?.invoke(true)
        viewDidAppearCompletion = null

        if (presentationDidFinishPrepare) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            paywallStatePublisher?.let {
                Superwall.instance.storePresentationObjects(request, it)
            }
        }

        unsavedOccurrence?.let {
            storage.coreDataManager.save(triggerRuleOccurrence = it, completion = null)
            unsavedOccurrence = null
        }

        isPresented = true

        Superwall.instance.dependencyContainer.delegateAdapter.didPresentPaywall(info)

        CoroutineScope(Dispatchers.IO).launch {
            print("*** TRACK OPEN*** ")
            trackOpen()
        }

         GameControllerManager.shared.setDelegate(this)

        // Focus the webView
        webView.requestFocus()

        // Grab the current orientation, to be able to set it back after the transaction abandon
        val currentOrientation = resources.configuration.orientation
        initialOrientation = currentOrientation

        presentationDidFinishPrepare = true
    }

    private var initialOrientation: Int? = null

    private suspend fun trackOpen() {
        val triggerSessionManager = factory.getTriggerSessionManager()
        triggerSessionManager.trackPaywallOpen()
        storage.trackPaywallOpen()
        val trackedEvent = InternalSuperwallEvent.PaywallOpen(info)
        Superwall.instance.track(trackedEvent)
    }

    private suspend fun trackClose() {
        val triggerSessionManager = factory.getTriggerSessionManager()

        // TODO: Update when surveys are added:
        val trackedEvent = InternalSuperwallEvent.PaywallClose(
            info
            // surveyPresentationResult
        )
        Superwall.instance.track(trackedEvent)
        triggerSessionManager.trackPaywallClose()
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

    // This is basically the same as `dismiss(animated: Bool)`
    // in the original iOS implementation
    private fun dismiss(presentationIsAnimated: Boolean) {
        // TODO: SW-2162 Implement animation support
        // https://linear.app/superwall/issue/SW-2162/%5Bandroid%5D-%5Bv1%5D-get-animated-presentation-working

        encapsulatingActivity?.finish()
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
        val activity = encapsulatingActivity.let { it } ?: return

        val alertController = AlertControllerFactory.make(
            context = activity,
            title = title,
            message = message,
            actionTitle = actionTitle,
            action = {
                action?.invoke()
            },
            onClose = {
                onClose?.invoke()
            }
        )
        alertController.show()
        loadingState = PaywallLoadingState.Ready()
    }

    //endregion

    //region State

    /**
     * Hides or displays the paywall spinner.
     *
     * @param isHidden A Boolean indicating whether to show or hide the spinner.
     */
    fun togglePaywallSpinner(isHidden: Boolean) {
        when {
            isHidden -> {
                if (loadingState is PaywallLoadingState.ManualLoading || loadingState is PaywallLoadingState.LoadingPurchase) {
                    loadingState = PaywallLoadingState.Ready()
                }
            }
            else -> {
                if (loadingState is PaywallLoadingState.Ready) {
                    loadingState = PaywallLoadingState.ManualLoading()
                }
            }
        }
    }

    internal fun loadingStateDidChange(from: PaywallLoadingState) {
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
                paywallInfo = this@PaywallViewController.info
            )
            Superwall.instance.track(trackedEvent)

            val triggerSessionManager = factory.getTriggerSessionManager()
            triggerSessionManager.trackWebviewLoad(
                forPaywallId = info.databaseId,
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
            isSafariVCPresented = true
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

    //region GameController
    override fun gameControllerEventDidOccur(event: GameControllerEvent) {
        val payload = event.jsonString
        webView.evaluateJavascript("window.paywall.accept([$payload])", null)
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallViewController,
            message = "Game controller event occurred: $payload"
        )
    }

    //endregion

    //region Misc

    // Android-specific
    fun prepareToDisplay() {
        // Check if the view already has a parent
        val parentViewGroup = this.parent as? ViewGroup
        parentViewGroup?.removeView(this)

        // This would normally be in iOS view will appear, but there's not a similar paradigm
        cache?.activePaywallVcKey = cacheKey
    }

    //endregion

}

interface ActivityEncapsulatable {
    var encapsulatingActivity: Activity?
}

class SuperwallPaywallActivity : Activity() {
    companion object {
        private const val VIEW_KEY = "viewKey"
        private const val PRESENTATION_STYLE_KEY = "presentationStyleKey"

        fun startWithView(
            context: Context,
            view: View,
            presentationStyleOverride: PaywallPresentationStyle? = null) {
            val key = UUID.randomUUID().toString()
            ViewStorage.storeView(key, view)

            val intent = Intent(context, SuperwallPaywallActivity::class.java).apply {
                putExtra(VIEW_KEY, key)
                putExtra(PRESENTATION_STYLE_KEY, presentationStyleOverride)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            context.startActivity(intent)
        }
    }

    private var contentView: View? = null

    override fun setContentView(view: View) {
        super.setContentView(view)
        contentView = view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        val key = intent.getStringExtra(VIEW_KEY)
        if (key == null) {
            finish() // Close the activity if there's no key
            return
        }

        val view = ViewStorage.retrieveView(key) ?: run {
            finish() // Close the activity if the view associated with the key is not found
            return
        }

        if (view is ActivityEncapsulatable) {
            view.encapsulatingActivity = this
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Now add
        setContentView(view)

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

        CoroutineScope(Dispatchers.IO).launch {
            paywallVc.viewWillDisappear()
        }
    }

    override fun onStop() {
        super.onStop()

        val paywallVc = contentView as? PaywallViewController ?: return

        CoroutineScope(Dispatchers.IO).launch {
            paywallVc.viewDidDisappear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clear reference to activity in the view
        (contentView as? ActivityEncapsulatable)?.encapsulatingActivity = null

        // Clear the reference to the contentView
        contentView = null
    }
}

object ViewStorage {
    private val views: MutableMap<String, View> = mutableMapOf()

    fun storeView(key: String, view: View) {
        views[key] = view
    }

    fun retrieveView(key: String): View? {
        return views.remove(key)
    }
}