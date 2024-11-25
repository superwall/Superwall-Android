package com.superwall.sdk.paywall.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvents
import com.superwall.sdk.config.models.OnDeviceCaching
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.TriggerFactory
import com.superwall.sdk.game.GameControllerDelegate
import com.superwall.sdk.game.GameControllerEvent
import com.superwall.sdk.game.GameControllerManager
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.AlertControllerFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallCacheLogic
import com.superwall.sdk.paywall.manager.PaywallViewCache
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.get_presentation_result.internallyGetPresentationResult
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.operators.storePresentationObjects
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.result.PresentationResult
import com.superwall.sdk.paywall.view.Survey.SurveyManager
import com.superwall.sdk.paywall.view.Survey.SurveyPresentationResult
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.delegate.PaywallViewDelegateAdapter
import com.superwall.sdk.paywall.view.delegate.PaywallViewEventCallback
import com.superwall.sdk.paywall.view.webview.PaywallMessage
import com.superwall.sdk.paywall.view.webview.SWWebView
import com.superwall.sdk.paywall.view.webview.SWWebViewDelegate
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandlerDelegate
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.lang.ref.WeakReference
import java.net.MalformedURLException
import java.net.URI
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

class PaywallView(
    context: Context,
    override var paywall: Paywall,
    val eventCallback: PaywallViewEventCallback? = null,
    var callback: PaywallViewDelegateAdapter? = null,
    val deviceHelper: DeviceHelper,
    val factory: Factory,
    val storage: LocalStorage,
    webView: SWWebView,
    private val cache: PaywallViewCache?,
    private val useMultipleUrls: Boolean,
) : FrameLayout(context),
    PaywallMessageHandlerDelegate,
    SWWebViewDelegate,
    ActivityEncapsulatable,
    GameControllerDelegate {
    private companion object {
        private val mainScope: MainScope = MainScope()
        private val ioScope: IOScope = IOScope()
        private val gameControllerJson by lazy {
            Json {
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
        }
    }

    interface Factory :
        TriggerFactory,
        OptionsFactory
    //region Public properties

    // MUST be set prior to presentation
    override var request: PresentationRequest? = null

    // We use a local webview so we can handle cases where webview process crashes
    private var _webView: SWWebView = webView
    override val webView: SWWebView = _webView

    //endregion

    //region Presentation properties

    // / The presentation style for the paywall.
    private var presentationStyle: PaywallPresentationStyle

    private var shimmerView: ShimmerView? = null

    private var loadingView: LoadingView? = null

    var paywallStatePublisher: MutableSharedFlow<PaywallState>? = null

    // The full screen activity instance if this view has been presented in one.
    override var encapsulatingActivity: WeakReference<Activity>? = null

    // / Stores the ``PaywallResult`` on dismiss of paywall.
    private var paywallResult: PaywallResult? = null

    // / Stores the completion block when calling dismiss.
    private var dismissCompletionBlock: (() -> Unit)? = null

    private var callbackInvoked = false

    private var viewCreatedCompletion: ((Boolean) -> Unit)? = null

    // / Defines when Browser is presenting in app.
    internal var isBrowserViewPresented = false

    internal var interceptTouchEvents = false

    // / Whether the survey was shown, not shown, or in a holdout. Defaults to not shown.
    private var surveyPresentationResult: SurveyPresentationResult = SurveyPresentationResult.NOSHOW

    //endregion

    // region State properties

    // / The paywall info
    override val info: PaywallInfo
        get() = paywall.getInfo(request?.presentationInfo?.eventData)

    // / The loading state of the paywall.
    override var loadingState: PaywallLoadingState = PaywallLoadingState.Unknown()
        set(value) {
            val oldValue = field
            field = value
            if (value::class != oldValue::class) {
                loadingStateDidChange(oldValue)
            }
        }

    // / Determines whether the paywall is presented or not.
    override val isActive: Boolean
        get() = isPresented

    // / Defines whether the view is being presented or not.
    private var isPresented = false
    private var presentationWillPrepare = true
    private var presentationDidFinishPrepare = false

    //endregion

    //region Private properties not used in initializer

    // / `true` if there's a survey to complete and the paywall is displayed in a modal style.
    private var didDisableSwipeForSurvey = false

    // / Whether the survey was shown, not shown, or in a holdout. Defaults to not shown.
    // TODO:
//    private var surveyPresentationResult: SurveyPresentationResult = .noShow

    // / If the user match a rule with an occurrence, this needs to be saved on
    // / paywall presentation.
    private var unsavedOccurrence: TriggerRuleOccurrence? = null

    private val cacheKey: String = PaywallCacheLogic.key(paywall.identifier, deviceHelper.locale)
    val backgroundColor: Int
        get() {

            val style =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                } else {
                    Configuration.UI_MODE_NIGHT_UNDEFINED
                }
            return when (style) {
                Configuration.UI_MODE_NIGHT_YES ->
                    paywall.darkBackgroundColor
                        ?: paywall.backgroundColor

                else -> paywall.backgroundColor
            }
        }
    //endregion

    //region Initialization

    init {
        id = View.generateViewId()
        setBackgroundColor(backgroundColor)
        presentationStyle = paywall.presentation.style
    }

    //endregion

    //region Public functions

    internal fun set(
        request: PresentationRequest,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
        unsavedOccurrence: TriggerRuleOccurrence?,
    ) {
        this.request = request
        this.paywallStatePublisher = paywallStatePublisher
        this.unsavedOccurrence = unsavedOccurrence
    }

    internal fun setupShimmer(shimmerView: ShimmerView) {
        this.shimmerView = shimmerView
        shimmerView.setupFor(this, loadingState)
    }

    internal fun setupLoading(loadingView: LoadingView) {
        this.loadingView = loadingView
        loadingView.setupFor(this, loadingState)
    }

    fun setupWith(
        shimmerView: ShimmerView,
        loadingView: LoadingView,
    ) {
        if (webView.parent == null) {
            addView(webView)
            webView.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        this.shimmerView = shimmerView
        this.loadingView = loadingView
    }

    fun present(
        presenter: Activity,
        request: PresentationRequest,
        unsavedOccurrence: TriggerRuleOccurrence?,
        presentationStyleOverride: PaywallPresentationStyle?,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
        completion: (Boolean) -> Unit,
    ) {
        if (webView.parent == null) addView(webView)
        cache?.acquireLoadingView()?.let {
            setupLoading(it)
        }

        cache?.acquireShimmerView()?.let {
            setupShimmer(it)
        }
        set(request, paywallStatePublisher, unsavedOccurrence)
        if (presentationStyleOverride != null && presentationStyleOverride != PaywallPresentationStyle.NONE) {
            presentationStyle = presentationStyleOverride
        } else {
            presentationStyle = paywall.presentation.style
        }

        SuperwallPaywallActivity.startWithView(
            presenter,
            this,
            cacheKey,
            presentationStyle,
        )
        viewCreatedCompletion = completion
    }

    fun beforeViewCreated() {
        if (isBrowserViewPresented) {
            return
        }
        shimmerView?.checkForOrientationChanges()
        presentationWillBegin()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = interceptTouchEvents

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

        callbackInvoked = false
        paywall.closeReason = PaywallCloseReason.None

        Superwall.instance.dependencyContainer.delegateAdapter
            .willPresentPaywall(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                // This call might fail sometimes due to webview inter
                webView.setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true)
            }
        }
        webView.scrollTo(0, 0)
        if (loadingState is PaywallLoadingState.Ready) {
            webView.messageHandler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
        }
        paywall.shimmerLoadingInfo.startAt = Date()
        trackShimmerStart()
        presentationWillPrepare = false
    }

    fun beforeOnDestroy() {
        if (isBrowserViewPresented) {
            return
        }
        Superwall.instance.presentationItems.paywallInfo = info
        Superwall.instance.dependencyContainer.delegateAdapter
            .willDismissPaywall(info)
    }

    suspend fun destroyed() {
        if (isBrowserViewPresented) {
            return
        }

        ioScope.launch {
            trackClose()
        }

        // Reset spinner
        val isShowingSpinner =
            loadingState is PaywallLoadingState.LoadingPurchase || loadingState is PaywallLoadingState.ManualLoading
        if (isShowingSpinner) {
            this.loadingState = PaywallLoadingState.Ready()
        }

        Superwall.instance.dependencyContainer.delegateAdapter
            .didDismissPaywall(info)

        val result = paywallResult ?: PaywallResult.Declined()

        paywallStatePublisher?.emit(PaywallState.Dismissed(info, result))

        if (!callbackInvoked) {
            callback?.onFinished(
                paywall = this,
                result = result,
                shouldDismiss = false,
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
        closeReason: PaywallCloseReason,
        completion: (() -> Unit)? = null,
    ) {
        dismissCompletionBlock = completion
        paywallResult = result
        paywall.closeReason = closeReason

        val isDeclined = paywallResult is PaywallResult.Declined
        val isManualClose = closeReason is PaywallCloseReason.ManualClose

        suspend fun dismissView() {
            if (isDeclined && isManualClose) {
                val trackedEvent = InternalSuperwallEvent.PaywallDecline(paywallInfo = info)

                val presentationResult =
                    withErrorTracking {
                        Superwall.instance.internallyGetPresentationResult(
                            event = trackedEvent,
                            isImplicit = true,
                        )
                    }.toResult()
                val paywallPresenterEvent = info.presentedByEventWithName
                val presentedByPaywallDecline =
                    paywallPresenterEvent == SuperwallEvents.PaywallDecline.rawName

                Superwall.instance.track(trackedEvent)
                val capturedResult = presentationResult.getOrNull()
                if (capturedResult != null && capturedResult is PresentationResult.Paywall && !presentedByPaywallDecline) {
                    // Logic here, similar to the Swift one
                    return
                }
            }

            callback?.let {
                callbackInvoked = true
                it.onFinished(
                    paywall = this,
                    result = result,
                    shouldDismiss = true,
                )
            } ?: run {
                // TODO: Add presentationIsAnimated here
                dismiss(presentationIsAnimated = false)
            }
        }

        val dismiss = {
            ioScope.launch {
                dismissView()
            }
        }

        SurveyManager.presentSurveyIfAvailable(
            paywall.surveys,
            paywallResult = result,
            paywallCloseReason = closeReason,
            activity =
                encapsulatingActivity?.get() ?: run {
                    dismiss()
                    return
                },
            paywallView = this,
            loadingState = loadingState,
            isDebuggerLaunched = request?.flags?.isDebuggerLaunched == true,
            paywallInfo = info,
            storage = storage,
            factory = factory,
        ) { res ->
            this.surveyPresentationResult = res
            dismiss()
        }
    }

    //endregion

    //region Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Assert if no `request`
        // fatalAssert(request != null, "Must be presenting a Paywallview with a `request` instance.")

        if (loadingState is PaywallLoadingState.Unknown) {
            loadWebView()
        }
    }

    // Lets the view know that presentation has finished.
    // Only called once per presentation.
    fun onViewCreated() {
        viewCreatedCompletion?.invoke(true)
        viewCreatedCompletion = null

        if (presentationDidFinishPrepare) {
            return
        }
        ioScope.launch {
            paywallStatePublisher?.let {
                Superwall.instance.storePresentationObjects(request, it)
            }
        }
        unsavedOccurrence?.let {
            storage.coreDataManager.save(triggerRuleOccurrence = it)
            unsavedOccurrence = null
        }

        isPresented = true
        Superwall.instance.dependencyContainer.delegateAdapter
            .didPresentPaywall(info)
        ioScope.launch {
            trackOpen()
        }
        GameControllerManager.shared.setDelegate(this@PaywallView)

        val currentOrientation = resources.configuration.orientation
        initialOrientation = currentOrientation
        presentationDidFinishPrepare = true
    }

    private var initialOrientation: Int? = null

    private suspend fun trackOpen() {
        storage.trackPaywallOpen()
        webView.messageHandler.handle(PaywallMessage.PaywallOpen)
        val trackedEvent = InternalSuperwallEvent.PaywallOpen(info)
        Superwall.instance.track(trackedEvent)
    }

    private suspend fun trackClose() {
        val trackedEvent =
            InternalSuperwallEvent.PaywallClose(
                info,
                surveyPresentationResult,
            )
        Superwall.instance.track(trackedEvent)
    }

    override fun eventDidOccur(paywallWebEvent: PaywallWebEvent) {
        ioScope.launch {
            eventCallback?.eventDidOccur(paywallWebEvent, this@PaywallView)
        }
    }

    //endregion

    //region Presentation

    // This is basically the same as `dismiss(animated: Bool)`
    // in the original iOS implementation
    private fun dismiss(presentationIsAnimated: Boolean) {
        // TODO: SW-2162 Implement animation support
        // https://linear.app/superwall/issue/SW-2162/%5Bandroid%5D-%5Bv1%5D-get-animated-presentation-working

        encapsulatingActivity?.get()?.finish()
    }

    private fun showLoadingView() {
        val transactionBackgroundView =
            Superwall.instance.options.paywalls.transactionBackgroundView
        if (transactionBackgroundView != PaywallOptions.TransactionBackgroundView.SPINNER) {
            return
        }
        loadingView?.let {
            mainScope.launch {
                it.visibility = View.VISIBLE
            }
        }
    }

    private fun hideLoadingView() {
        loadingView?.let {
            mainScope.launch {
                it.visibility = View.GONE
            }
        }
    }

    private fun trackShimmerStart() {
        val trackedEvent =
            InternalSuperwallEvent.ShimmerLoad(
                state = InternalSuperwallEvent.ShimmerLoad.State.Started,
                paywallId = paywall.identifier,
                visibleDuration = null,
                preloadingEnabled = factory.makeSuperwallOptions().paywalls.shouldPreload,
                delay = paywall.presentation.delay.toDouble(),
            )
        ioScope.launch {
            Superwall.instance.track(trackedEvent)
        }
    }

    private fun showShimmerView() {
        shimmerView?.let {
            mainScope.launch {
                it.showShimmer()
            }
        }
    }

    private fun hideShimmerView() {
        shimmerView?.let {
            mainScope.launch {
                it.hideShimmer()
            }
        }
        val visible = paywall.shimmerLoadingInfo.startAt
        val now = Date()
        paywall.shimmerLoadingInfo.endAt = now
        ioScope.launch {
            val trackedEvent =
                InternalSuperwallEvent.ShimmerLoad(
                    state = InternalSuperwallEvent.ShimmerLoad.State.Complete,
                    paywallId = paywall.identifier,
                    visibleDuration =
                        if (visible != null) {
                            (now.time - visible.time).milliseconds.toDouble(
                                DurationUnit.MILLISECONDS,
                            )
                        } else {
                            0.0
                        },
                    delay = paywall.presentation.delay.toDouble(),
                    preloadingEnabled = factory.makeSuperwallOptions().paywalls.shouldPreload,
                )
            Superwall.instance.track(trackedEvent)
        }
    }

    fun showRefreshButtonAfterTimeout(isVisible: Boolean) {
        // TODO: Implement this
    }

    fun showAlert(
        title: String? = null,
        message: String? = null,
        actionTitle: String? = null,
        closeActionTitle: String = "Done",
        action: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    ) {
        val activity = encapsulatingActivity?.get() ?: return

        val alertController =
            AlertControllerFactory.make(
                context = activity,
                title = title,
                message = message,
                actionTitle = actionTitle,
                action = {
                    action?.invoke()
                },
                onClose = {
                    onClose?.invoke()
                },
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
        if (isActive) {
            mainScope.launch {
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
                    }

                    is PaywallLoadingState.Ready -> {
                        ioScope.launch {
                            delay(paywall.presentation.delay)
                            mainScope.launch {
                                showRefreshButtonAfterTimeout(false)
                                hideLoadingView()
                                hideShimmerView()
                            }
                        }
                    }
                }
            }
        }
    }

    fun loadWebView() {
        ioScope.launch {
            val url = paywall.url
            if (paywall.webviewLoadingInfo.startAt == null) {
                paywall.webviewLoadingInfo.startAt = Date()
            }

            launch {
                val trackedEvent =
                    InternalSuperwallEvent.PaywallWebviewLoad(
                        state = InternalSuperwallEvent.PaywallWebviewLoad.State.Start(),
                        paywallInfo = this@PaywallView.info,
                    )
                Superwall.instance.track(trackedEvent)
            }

            webView.onRenderProcessCrashed = {
                val isOverO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallView,
                    message =
                        "Webview Process has crashed for paywall with identifier: ${paywall.identifier}.\n" +
                            "Crashed by the system: ${
                                if (isOverO)it.didCrash() else "Unknown"} - priority ${
                                if (isOverO)it.rendererPriorityAtExit() else "Unknown"}",
                )
                recreateWebview()
            }

            webView.scrollEnabled = paywall.isScrollEnabled ?: true
            mainScope.launch {
                if (paywall.onDeviceCache is OnDeviceCaching.Enabled) {
                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                } else {
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }
                if (useMultipleUrls) {
                    webView.loadPaywallWithFallbackUrl(paywall)
                } else {
                    webView.loadUrl(url.value)
                }
            }
            loadingState = PaywallLoadingState.LoadingURL()
        }
    }

    private fun recreateWebview() {
        removeView(webView)
        _webView = SWWebView(context, webView.messageHandler)
        addView(webView)
        webView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        webView.messageHandler.handle(PaywallMessage.PaywallOpen)
        loadWebView()
    }

//endregion

//region Deep linking

    override fun presentBrowserInApp(url: String) {
        try {
            val parsedUrl = URI(url)
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            customTabsIntent.launchUrl(context, Uri.parse(parsedUrl.toString()))
            isBrowserViewPresented = true
        } catch (e: MalformedURLException) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallView,
                message = "Invalid URL provided for \"Open In-App URL\" click behavior.",
            )
        } catch (e: Throwable) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallView,
                message = "Exception thrown for \"Open In-App URL\" click behavior.",
            )
        }
    }

    override fun presentBrowserExternal(url: String) {
        try {
            val parsedUrl = URI(url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(parsedUrl.toString()))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: MalformedURLException) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallView,
                message = "Invalid URL provided for \"Open External URL\" click behavior.",
            )
        } catch (e: Throwable) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallView,
                message = "Exception thrown for \"Open External URL\" click behavior.",
            )
        }
    }

    override fun openDeepLink(url: String) {
        // TODO add track paywall dismiss
        var uri = Uri.parse(url)
        eventDidOccur(PaywallWebEvent.OpenedDeepLink(uri))
        val context = encapsulatingActivity?.get()
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, uri)
        context?.startActivity(deepLinkIntent)
    }

    //region GameController
    override fun gameControllerEventOccured(event: GameControllerEvent) {
        val payload =
            try {
                gameControllerJson.encodeToString(event)
            } catch (e: Throwable) {
                null
            }
        webView.evaluateJavascript("window.paywall.accept([$payload])", null)
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallView,
            message = "Game controller event occurred: $payload",
        )
    }

//endregion

//region Misc

    // Android-specific
    fun prepareToDisplay() {
        // Check if the view already has a parent
        val parentViewGroup = this@PaywallView.parent as? ViewGroup
        parentViewGroup?.removeView(this@PaywallView)

        // This would normally be in iOS view will appear, but there's not a similar paradigm
        cache?.activePaywallVcKey = cacheKey
    }

    fun cleanup() {
        encapsulatingActivity?.clear()
        callback = null
        (parent as? ViewGroup)?.removeAllViews()
        removeAllViews()
        detachAllViewsFromParent()
    }

//endregion
}

interface ActivityEncapsulatable {
    var encapsulatingActivity: WeakReference<Activity>?
}
