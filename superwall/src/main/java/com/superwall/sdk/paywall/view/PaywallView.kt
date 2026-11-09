package com.superwall.sdk.paywall.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color.*
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.content.res.AppCompatResources
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsIntent.COLOR_SCHEME_LIGHT
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import com.superwall.sdk.R
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEvents
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.config.options.computedShouldPreload
import com.superwall.sdk.dependencies.AttributesFactory
import com.superwall.sdk.dependencies.CustomerInfoFactory
import com.superwall.sdk.dependencies.DelegateAdapterFactory
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.EnrichmentFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.PresentationFactory
import com.superwall.sdk.dependencies.TrackingFactory
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
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallViewCache
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.result.PresentationResult
import com.superwall.sdk.paywall.view.PaywallViewState.Updates.*
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.delegate.PaywallViewDelegateAdapter
import com.superwall.sdk.paywall.view.delegate.PaywallViewEventCallback
import com.superwall.sdk.paywall.view.survey.SurveyManager
import com.superwall.sdk.paywall.view.webview.CustomTabsHelper
import com.superwall.sdk.paywall.view.webview.PaywallUIDelegate
import com.superwall.sdk.paywall.view.webview.PaywallWebUI
import com.superwall.sdk.paywall.view.webview.SWWebView
import com.superwall.sdk.paywall.view.webview.SendPaywallMessages
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessage
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandlerDelegate
import com.superwall.sdk.paywall.view.webview.messaging.PaywallStateDelegate
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.utilities.withErrorTracking
import com.superwall.sdk.web.WebPaywallRedeemer
import com.superwall.sdk.misc.primitives.StateActor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.lang.ref.WeakReference
import java.util.Date
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class PaywallView internal constructor(
    context: Context,
    val eventCallback: PaywallViewEventCallback?,
    var callback: PaywallViewDelegateAdapter?,
    val deviceHelper: DeviceHelper,
    val factory: Factory,
    val storage: LocalStorage,
    webView: PaywallWebUI,
    private val cache: PaywallViewCache?,
    val controller: PaywallController,
    val sendMessages: SendPaywallMessages,
    private val redeemer: WebPaywallRedeemer,
    private val mainScope: MainScope,
    private val ioScope: IOScope,
) : FrameLayout(context),
    PaywallMessageHandlerDelegate,
    PaywallUIDelegate,
    PaywallStateDelegate by controller,
    ActivityEncapsulatable,
    GameControllerDelegate,
    SendPaywallMessages by sendMessages {
    /**
     * Public signature kept for compatibility. The SDK builds views through the internal
     * constructor with its own scopes; this one gives the view scopes of its own and never
     * reads the SDK's graph.
     */
    constructor(
        context: Context,
        eventCallback: PaywallViewEventCallback? = null,
        callback: PaywallViewDelegateAdapter? = null,
        deviceHelper: DeviceHelper,
        factory: Factory,
        storage: LocalStorage,
        webView: PaywallWebUI,
        cache: PaywallViewCache?,
        controller: PaywallController,
        sendMessages: SendPaywallMessages,
        redeemer: WebPaywallRedeemer,
    ) : this(
        context = context,
        eventCallback = eventCallback,
        callback = callback,
        deviceHelper = deviceHelper,
        factory = factory,
        storage = storage,
        webView = webView,
        cache = cache,
        controller = controller,
        sendMessages = sendMessages,
        redeemer = redeemer,
        mainScope = MainScope(),
        ioScope = IOScope(),
    )

    /**
     * Holds the paywall's [PaywallViewState] in a [StateActor]. Every [updateState] is applied
     * through the actor as a [PaywallReducer], so state changes go through the same reducer
     * pipeline (and interceptors) as the SDK's other actors.
     */
    class PaywallController internal constructor(
        initialState: PaywallViewState,
        scope: CoroutineScope,
    ) : PaywallStateDelegate {
        /** Public signature kept for compatibility; the controller gets a scope of its own. */
        constructor(initialState: PaywallViewState) : this(initialState, IOScope())

        internal val actor: StateActor<PaywallContext, PaywallViewState> =
            StateActor(initialState, scope)

        val currentState: StateFlow<PaywallViewState> = actor.state

        override val state: PaywallViewState
            get() = actor.state.value

        override fun updateState(update: PaywallViewState.Updates) {
            actor.update(PaywallReducer(update))
        }
    }

    private companion object {
        private val gameControllerJson by lazy {
            Json {
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
        }
    }

    interface Factory :
        TriggerFactory,
        OptionsFactory,
        DeviceHelperFactory,
        AttributesFactory,
        EnrichmentFactory,
        TrackingFactory,
        DelegateAdapterFactory,
        CustomerInfoFactory,
        PresentationFactory
    //region Public properties

    // We use a local webview so we can handle cases where webview process crashes
    internal var webView: PaywallWebUI = webView
        private set

    fun scrollBy(y: Int) {
        webView.scrollBy(0, y)
    }

    fun scrollTo(y: Int) {
        webView.scrollTo(0, y)
    }

    //endregion

    //region Presentation properties

    // The full screen activity instance if this view has been presented in one.
    // For an in-window presentation this is the host app's Activity, which must never be finished.
    override var encapsulatingActivity: WeakReference<Activity>? = null

    // Set while this view is presented in-window (see [PaywallOptions.PresentationHost.IN_WINDOW]).
    @Volatile
    internal var inWindowHost: InWindowPaywallHost? = null

    // Set once this view, embedded in the app's own Activity (e.g. by PaywallComposable), schedules
    // notifications. It outlives cleanup() so a permission granted after the paywall is gone still
    // schedules its reminders.
    private var embeddedNotifications: PaywallNotifications? = null

    /** The notifications of this view while it is embedded in [activity]. */
    @Synchronized
    internal fun embeddedNotifications(activity: Activity): PaywallNotifications =
        embeddedNotifications?.takeIf { it.activity === activity }
            ?: PaywallNotifications.forEmbeddingActivity(activity).also { embeddedNotifications = it }

    private var shimmerView: PaywallShimmerView? = null

    private var loadingView: PaywallPurchaseLoadingView? = null

    //endregion
    // region State properties

    // / The paywall info
    override val info: PaywallInfo
        get() = controller.state.info

    // / The loading state of the paywall.
    var loadingState: PaywallLoadingState
        get() = state.loadingState
        private set(value) {
            controller.updateState(SetLoadingState(value))
        }

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
                    state.paywall.darkBackgroundColor
                        ?: state.paywall.backgroundColor

                else -> state.paywall.backgroundColor
            }
        }
    //endregion

    //region Initialization

    private var stateListener: Job? = null
    private var customerInfoListener: Job? = null

    private fun stopStateListener() {
        stateListener?.cancel()
        stateListener = null
        customerInfoListener?.cancel()
        customerInfoListener = null
    }

    private fun startStateListener() {
        // Ensure any previous listener is cancelled before starting a new one
        stopStateListener()
        stateListener =
            ioScope.launch {
                controller.currentState
                    .map { it.loadingState }
                    .distinctUntilChanged { old, new -> old::class == new::class }
                    .collectLatest { loadingStateDidChange() }
            }
        customerInfoListener =
            ioScope.launch {
                controller.actor.immediate(paywallContext, PaywallActions.ObserveCustomerInfo)
            }
    }

    /** The context the paywall's actions run in, over [controller]'s actor. */
    private val paywallContext: PaywallContext =
        PaywallContextImpl(
            actor = controller.actor,
            scope = ioScope,
            storage = storage,
            tracker = { factory.track(it) },
            customerInfo = { factory.customerInfoFlow() },
        )

    /**
     * The presentation's load watch and deferred webview failure. Runs on the main scope, like the
     * dismissal it can start and the view-creation callback it waits for.
     */
    private val presentationLifecycle: PaywallPresentationLifecycle =
        PaywallPresentationLifecycle(
            scope = mainScope,
            loadWatchScope = ioScope,
            storage = storage,
            tracker = { factory.track(it) },
            paywallState = { controller.state },
            paywallStates = controller.currentState,
            applyPaywallUpdate = { controller.updateState(it) },
            dismiss = {
                dismiss(
                    PaywallResult.Declined(),
                    PaywallCloseReason.WebViewFailedToLoad,
                )
            },
        )

    /** The shimmer and purchase spinner. Runs on the main scope, where their views live. */
    private val loadingOverlay: PaywallLoadingOverlay =
        PaywallLoadingOverlay(
            scope = mainScope,
            delayScope = ioScope,
            storage = storage,
            tracker = { factory.track(it) },
            paywallState = { controller.state },
            transactionBackgroundView = { factory.makeSuperwallOptions().paywalls.transactionBackgroundView },
            preloadingEnabled = { factory.makeSuperwallOptions().computedShouldPreload(deviceHelper.deviceTier) },
            applyPaywallUpdate = { controller.updateState(it) },
            views = { PaywallLoadingOverlay.OverlayViews(shimmer = shimmerView, spinner = loadingView) },
        )

    /** Web checkout, browsers and deep links: everything that sends the user out of the paywall. */
    private val externalNavigator: PaywallExternalNavigator =
        PaywallExternalNavigator(
            scope = mainScope,
            storage = storage,
            tracker = { factory.track(it) },
            checkoutLauncher = ::launchCheckout,
            customTab = { url ->
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                customTabsIntent.launchUrl(context, url.toUri())
            },
            externalBrowser = { url ->
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            deepLink = { url ->
                val activityContext = encapsulatingActivity?.get()
                val deepLinkIntent =
                    Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                        if (activityContext == null) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                (activityContext ?: context).startActivity(deepLinkIntent)
            },
            onDeepLinkOpened = { url -> eventDidOccur(PaywallWebEvent.OpenedDeepLink(url.toUri())) },
            applyPaywallUpdate = { controller.updateState(it) },
        )

    /**
     * Hands web checkout to the in-window host, which never registers the activity launcher
     * (registering one with a lifecycle on an already started host throws), or to the launcher.
     */
    private fun launchCheckout(url: String): CheckoutLaunch {
        inWindowHost?.let {
            it.launchCheckout(url)
            return CheckoutLaunch.Launched
        }
        val launcher = activityResultLauncher ?: return CheckoutLaunch.NoLauncher
        launcher.launch(url)
        return CheckoutLaunch.Launched
    }

    init {
        id = View.generateViewId()
        setBackgroundColor(backgroundColor)
    }

    //endregion

    internal fun set(
        request: PresentationRequest,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
        unsavedOccurrence: TriggerRuleOccurrence?,
        experiment: Experiment?,
    ) {
        controller.updateState(
            SetRequest(
                request,
                paywallStatePublisher,
                unsavedOccurrence,
                experiment,
            ),
        )
    }

    internal fun setupShimmer(shimmerView: PaywallShimmerView) {
        this.shimmerView = shimmerView
        if (shimmerView is View) {
            // Note: This always _is_ true, but the compiler doesn't know that
            shimmerView.setupFor(this, loadingState)
        }
    }

    internal fun setupLoading(loadingView: PaywallPurchaseLoadingView) {
        this.loadingView = loadingView
        if (loadingView is View) {
            loadingView.setupFor(this, loadingState)
        }
    }
    //region Public functions

    fun setupWith(
        shimmerView: PaywallShimmerView,
        loadingView: PaywallPurchaseLoadingView,
    ) {
        webView.attach(this)
        this.shimmerView = shimmerView
        this.loadingView = loadingView
    }

    fun present(
        presenter: Activity,
        request: PresentationRequest,
        unsavedOccurrence: TriggerRuleOccurrence?,
        presentationStyleOverride: PaywallPresentationStyle?,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
        experiment: Experiment?,
        completion: (Boolean) -> Unit,
    ) {
        webView.attach(this)
        cache?.acquireLoadingView()?.let {
            setupLoading(it)
        }

        presentationLifecycle.dispatch(
            PresentationLifecycleState.Actions.WatchLoad(
                factory.makeSuperwallOptions().paywalls.timeoutAfter,
            ),
        )

        cache?.acquireShimmerView()?.let {
            setupShimmer(it)
        }
        set(request, paywallStatePublisher, unsavedOccurrence, experiment)
        controller.updateState(
            SetPresentationConfig(
                presentationStyleOverride,
                completion,
            ),
        )

        if (factory.makeSuperwallOptions().paywalls.presentationHost == PaywallOptions.PresentationHost.IN_WINDOW) {
            InWindowPaywallHost
                .show(
                    presenter,
                    this,
                    state.presentationStyle,
                ).getOrThrow()
        } else {
            SuperwallPaywallActivity
                .startWithViewForPresentation(
                    presenter,
                    this,
                    state.cacheKey,
                    state.presentationStyle,
                ).getOrThrow()
        }
        startStateListener()
    }

    internal fun clearActivityLaunchState() {
        controller.updateState(ClearViewCreatedCompletion)
        cache?.activePaywallVcKey = null
    }

    /**
     * The webview can't load this paywall: every URL and retry is used up, or the app's
     * `paywalls.timeoutAfter` passed. [PresentationLifecycleState.Actions.WebviewFailed] decides
     * what that does to the presentation.
     */
    internal fun webViewDidFail() {
        presentationLifecycle.dispatch(PresentationLifecycleState.Actions.WebviewFailed)
    }

    internal fun handleActivityLaunchFailure(
        error: Throwable,
        emitPresentationError: Boolean,
    ) {
        Logger.debug(
            logLevel = LogLevel.error,
            scope = LogScope.paywallPresentation,
            message = error.message ?: "Unable to launch SuperwallPaywallActivity",
            error = error,
        )
        if (emitPresentationError) {
            state.paywallStatePublisher?.let { publisher ->
                ioScope.launch {
                    publisher.emit(PaywallState.PresentationError(error))
                }
            }
        }
    }

    override fun updateState(update: PaywallViewState.Updates) {
        controller.updateState(update)
        // The paywall has its templates: a preload waiting off screen can stop rendering.
        if (update is PaywallViewState.Updates.SetLoadingState && update.newState is PaywallLoadingState.Ready) {
            mainScope.launch { webView.onPaywallReady() }
        }
    }

    override val state: PaywallViewState
        get() = controller.state

    fun beforeViewCreated() {
        if (state.isBrowserViewPresented) {
            return
        }
        shimmerView?.checkForOrientationChanges()
        presentationWillBegin()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = state.interceptTouchEvents

    private fun presentationWillBegin() {
        if (!state.presentationWillPrepare || state.presentationDidFinishPrepare) {
            return
        }
        controller.updateState(PresentationWillBegin)
        // A new presentation is being prepared on this view (the guard above excludes one that
        // already finished preparing), so mint its id now: willPresentPaywall(info),
        // didPresentPaywall(info) and the paywall_open that follows must all report the same id.
        // onViewCreated() runs the same idempotent update for hosts that skip beforeViewCreated().
        controller.updateState(PaywallViewState.Updates.BeginPresentation)

        factory
            .delegate()
            .willPresentPaywall(info)
            /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    // Temporary disabled
                    // webView.setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true)
                } catch (e: Throwable) {
                    Logger.debug(
                        LogLevel.info,
                        LogScope.paywallView,
                        "Cannot set webview priority when beginning presentation",
                        error = e,
                    )
                }
            }*/
        webView.scrollTo(0, 0)
        if (loadingState is PaywallLoadingState.Ready) {
            webView.messageHandler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
        } else if (loadingState is PaywallLoadingState.LoadingURL || loadingState is PaywallLoadingState.Unknown) {
            if (state.webviewFailedToLoad) {
                // The last load failed for good while this paywall was off-screen (e.g. during
                // preload). Try again now that it's wanted, like iOS does for a `didFailToLoad`
                // webview. A load that is still retrying isn't restarted.
                loadWebView()
            }
            controller.updateState(ShimmerStarted)
            loadingOverlay.dispatch(LoadingOverlayState.Actions.TrackShimmerStart)
        }
    }

    fun beforeOnDestroy(forceCleanup: Boolean = false) {
        if (!forceCleanup) {
            return
        }
        factory.updatePaywallInfo(info)
        factory
            .delegate()
            .willDismissPaywall(info)
    }

    suspend fun destroyed(forceCleanup: Boolean = false) {
        if (!forceCleanup) {
            if (state.isPresented && !state.isBrowserViewPresented && !state.closedForBackground) {
                controller.updateState(SetClosedForBackground(true))
                ioScope.launch {
                    trackClose()
                }
            }
            return
        }

        // Ensure we stop listening to state changes when being destroyed
        stopStateListener()

        // A paywall closed for background already tracked its paywall_close when it left the
        // screen. It is torn down without coming back, e.g. its activity was finished while
        // stopped, so tracking another close would count the same close twice.
        if (!state.closedForBackground) {
            ioScope.launch {
                trackClose()
            }
        }

        // Reset spinner
        val isShowingSpinner =
            loadingState is PaywallLoadingState.LoadingPurchase || loadingState is PaywallLoadingState.ManualLoading
        if (isShowingSpinner) {
            controller.updateState(SetLoadingState(PaywallLoadingState.Ready))
        }

        factory
            .delegate()
            .didDismissPaywall(info)

        val result = state.paywallResult ?: PaywallResult.Declined()

        state.paywallStatePublisher?.emit(PaywallState.Dismissed(info, result))

        if (!state.callbackInvoked) {
            controller.updateState(CallbackInvoked)
            callback?.onFinished(
                paywall = this,
                result = result,
                shouldDismiss = false,
            )
        }

        if (state.paywall.closeReason.stateShouldComplete) {
            controller.updateState(ClearStatePublisher)
        }

        GameControllerManager.shared.clearDelegate(this)
        resetPresentationPreparations()

        controller.updateState(CleanupAfterDestroy)
        cache?.activePaywallVcKey = null
    }

    private fun resetPresentationPreparations() {
        controller.updateState(ResetPresentationPreparations)
    }

    // A cached view can be re-presented after a previous presentation was stopped without a
    // finishing teardown - the purchase billing sheet (or backgrounding) triggers a non-finishing
    // onStop, which since #431 intentionally no longer tears the view down. That leaks
    // per-presentation transient state into the next present: a stale LoadingPurchase/ManualLoading
    // spinner swallows taps, and a stale presentationDidFinishPrepare makes onViewCreated()
    // early-return without re-wiring the view. Called from PaywallManager.getPaywallView's cache-hit
    // branch when a cached view is handed back for a new presentation, so every new presentation -
    // full-screen register() and embedded getPaywallView/getPaywall alike - gets a clean slate.
    // Safe because that branch only runs for a genuinely new presentation request - never on
    // resume-same-instance (that goes through onResume -> onViewCreated) nor during an in-flight
    // purchase.
    //
    // This also runs when the view is still presented and attached: the only request that reaches
    // the cache-hit branch while a paywall is on screen is a getPaywall() for that same paywall
    // (register() is rejected earlier by checkNoPaywallAlreadyPresented), and that call always
    // takes the view over - prepareToDisplay() removes it from its current parent in the same
    // call. The view cannot tell that apart from an embedded host re-presenting a detached view,
    // and it does not need to: both are the next presentation of this view, so both get a clean
    // slate and a new presentation id (minted in beforeViewCreated()/onViewCreated()). The
    // presentation being taken over ends here without a paywall_close of its own.
    internal fun resetTransientPresentationState() {
        if (loadingState is PaywallLoadingState.LoadingPurchase ||
            loadingState is PaywallLoadingState.ManualLoading
        ) {
            controller.updateState(SetLoadingState(PaywallLoadingState.Ready))
        }
        presentationLifecycle.dispatch(PresentationLifecycleState.Actions.ResetForNewPresentation)
        resetPresentationPreparations()
    }

    internal fun dismiss(
        result: PaywallResult,
        closeReason: PaywallCloseReason,
        completion: (() -> Unit)? = null,
    ) {
        presentationLifecycle.dispatch(PresentationLifecycleState.Actions.StopWatchingLoad)
        controller.updateState(
            InitiateDismiss(
                result,
                closeReason,
                completion,
            ),
        )

        val isDeclined = state.paywallResult is PaywallResult.Declined
        val isManualClose = closeReason is PaywallCloseReason.ManualClose

        suspend fun dismissView() {
            // Get the paywall state from the webview before dismissing
            val paywallState = webView.messageHandler.getState()
            controller.updateState(SetPaywallState(paywallState))

            if (isDeclined && isManualClose) {
                val trackedEvent = InternalSuperwallEvent.PaywallDecline(paywallInfo = info)

                val presentingPlacement = info.presentedByEventWithName
                val presentedByPaywallDecline =
                    presentingPlacement == SuperwallEvents.PaywallDecline.rawName
                val presentedByTransactionAbandon =
                    presentingPlacement == SuperwallEvents.TransactionAbandon.rawName
                val presentedByTransactionFail =
                    presentingPlacement == SuperwallEvents.TransactionFail.rawName

                // Don't track PaywallDecline if this paywall was already presented by PaywallDecline
                // to prevent infinite loops
                if (!presentedByPaywallDecline) {
                    val presentationResult =
                        withErrorTracking {
                            factory.internallyGetPresentationResult(
                                event = trackedEvent,
                                isImplicit = true,
                            )
                        }.toResult()

                    factory.track(trackedEvent).getOrNull()
                    val capturedResult = presentationResult.getOrNull()
                    if (capturedResult != null &&
                        capturedResult is PresentationResult.Paywall &&
                        !presentedByTransactionAbandon &&
                        !presentedByTransactionFail
                    ) {
                        // If a paywall_decline trigger is active and the current paywall wasn't presented
                        // by transaction_abandon or transaction_fail, it lands here so
                        // as not to dismiss the paywall. track() will do that before presenting the next paywall.
                        return
                    }
                }
            }

            callback?.let {
                // A callback-backed (embedded) paywall delegates teardown to its host, which
                // may legitimately keep the view on screen (e.g. a tab-bar embed). The purchase
                // spinner is otherwise only reset in the full destroy() teardown, so without
                // this an embedded paywall that stays visible after a purchase spins forever.
                if (loadingState is PaywallLoadingState.LoadingPurchase ||
                    loadingState is PaywallLoadingState.ManualLoading
                ) {
                    controller.updateState(SetLoadingState(PaywallLoadingState.Ready))
                }
                controller.updateState(CallbackInvoked)
                it.onFinished(
                    paywall = this,
                    result = result,
                    shouldDismiss = true,
                )
            } ?: run {
                dismiss(presentationIsAnimated = false)
            }
            stopStateListener()
        }

        val finalDismiss = {
            ioScope.launch {
                dismissView()
            }
        }

        if (isPaymentSheetPresented) {
            beforeOnDestroy()
            cleanup()
            paymentSheetNotOpened()
        }

        SurveyManager.presentSurveyIfAvailable(
            state.paywall.surveys,
            paywallResult = result,
            paywallCloseReason = closeReason,
            activity =
                encapsulatingActivity?.get() ?: run {
                    finalDismiss()
                    return
                },
            paywallView = this,
            loadingState = loadingState,
            isDebuggerLaunched = state.request?.flags?.isDebuggerLaunched == true,
            paywallInfo = info,
            storage = storage,
            factory = factory,
            track = { factory.track(it) },
        ) { res ->
            controller.updateState(UpdateSurveyState(res))
            finalDismiss()
        }
    }

//endregion

//region Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Assert if no `request`
        // fatalAssert(request != null, "Must be presenting a Paywallview with a `request` instance.")

        // Ensure state listener is running for both normal and Compose presentation flows
        startStateListener()

        if (loadingState is PaywallLoadingState.Unknown) {
            loadWebView()
        }
    }

    fun registerPaymentSheetLauncher(
        lifecycleOwner: LifecycleOwner,
        registryOwner: ActivityResultRegistryOwner,
    ) {
        registerIntent(lifecycleOwner, registryOwner)
    }

    // Lets the view know that presentation has finished.
// Only called once per presentation.
    fun onViewCreated() {
        val isNewPresentation = !state.presentationDidFinishPrepare
        if (isNewPresentation) {
            // Before the completion below emits PaywallState.Presented(info): a new presentation
            // on a view whose current id was already consumed by a paywall_open gets a fresh one.
            controller.updateState(PaywallViewState.Updates.BeginPresentation)
        }
        state.viewCreatedCompletion?.invoke(true)
        controller.updateState(ClearViewCreatedCompletion)

        if (!isNewPresentation) {
            if (state.closedForBackground) {
                controller.updateState(SetClosedForBackground(false))
                ioScope.launch {
                    trackOpen()
                }
            }
            return
        }
        ioScope.launch {
            state.paywallStatePublisher?.let {
                factory.storePresentationObject(state.request, it)
            }
        }
        state.unsavedOccurrence?.let {
            storage.coreDataManager.save(triggerRuleOccurrence = it)
            controller.updateState(ClearUnsavedOccurrence)
        }

        controller.updateState(SetPresentedAndFinished)
        factory
            .delegate()
            .didPresentPaywall(info)
        presentationLifecycle.dispatch(PresentationLifecycleState.Actions.ViewCreated)
        loadingStateDidChange()
        webView.messageHandler.flushPendingMessages()
        ioScope.launch {
            trackOpen()
        }
        GameControllerManager.shared.setDelegate(this@PaywallView)

        val currentOrientation = resources.configuration.orientation
        initialOrientation = currentOrientation
    }

    private var initialOrientation: Int? = null

    private suspend fun trackOpen() {
        controller.updateState(PaywallViewState.Updates.SetLastOpen)
        storage.trackPaywallOpen()
        webView.messageHandler.handle(PaywallMessage.PaywallOpen)
        val trackedEvent =
            InternalSuperwallEvent.PaywallOpen(
                info,
                factory.getCurrentUserAttributes(),
                demandTier = factory.demandTier(),
                demandScore = factory.demandScore(),
            )
        factory.track(trackedEvent)
    }

    private suspend fun trackClose() {
        val trackedEvent =
            InternalSuperwallEvent.PaywallClose(
                info,
                state.surveyPresentationResult,
            )
        factory.track(trackedEvent)
    }

    override fun eventDidOccur(paywallWebEvent: PaywallWebEvent) {
        ioScope.launch {
            eventCallback?.eventDidOccur(paywallWebEvent, this@PaywallView)
        }
    }

//endregion

//region Presentation

    private fun dismiss(presentationIsAnimated: Boolean) {
        // TODO: SW-2162 Implement animation support
        // https://linear.app/superwall/issue/SW-2162/%5Bandroid%5D-%5Bv1%5D-get-animated-presentation-working
        inWindowHost?.let {
            it.close()
            return
        }
        val activity = encapsulatingActivity?.get() ?: return
        if (activity !is SuperwallPaywallActivity &&
            factory.makeSuperwallOptions().paywalls.presentationHost == PaywallOptions.PresentationHost.IN_WINDOW
        ) {
            // An in-window presentation that already closed: the activity is the host app's own.
            return
        }
        activity.finish()
    }

    fun onThemeChanged() {
        webView.messageHandler.handle(PaywallMessage.TemplateParamsAndUserAttributes)
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
        val activity =
            encapsulatingActivity?.get() ?: kotlin.run {
                return
            }

        mainScope.launch {
            val alertController =
                AlertControllerFactory.make(
                    context = activity,
                    title = title,
                    message = message,
                    actionTitle = actionTitle,
                    closeActionTitle = closeActionTitle,
                    action = {
                        action?.invoke()
                    },
                    onClose = {
                        onClose?.invoke()
                    },
                )
            alertController.show()

            controller.updateState(SetLoadingState(PaywallLoadingState.Ready))
        }
    }

//endregion

//region State

    /** Hands the current loading state to the shimmer and spinner overlay. */
    internal fun loadingStateDidChange() {
        loadingOverlay.dispatch(
            LoadingOverlayState.Actions.LoadingStateChanged(
                current = loadingState,
                isPresented = state.isPresented,
            ),
        )
    }

    fun loadWebView() {
        ioScope.launch {
            val url = state.paywall.url
            controller.updateState(WebLoadingStarted)

            launch {
                val trackedEvent =
                    InternalSuperwallEvent.PaywallWebviewLoad(
                        state = InternalSuperwallEvent.PaywallWebviewLoad.State.Start(),
                        paywallInfo = this@PaywallView.info,
                    )
                factory.track(trackedEvent)
            }

            webView.setup(
                url = url,
                onRenderCrashed = { didCrash, priority ->
                    val isOverO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    controller.updateState(CrashRetry)
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallView,
                        message =
                            "Webview Process has crashed for paywall with identifier: ${state.paywall.identifier}.\n" +
                                "Crashed by the system: ${
                                    if (isOverO) didCrash else "Unknown"
                                } - priority ${
                                    if (isOverO) priority else "Unknown"
                                }",
                    )
                    if (state.crashRetries < 3) {
                        Logger.debug(
                            LogLevel.error,
                            LogScope.paywallView,
                            "Webview crash - recreating ${state.paywall.identifier}",
                        )

                        recreateWebview()
                    } else {
                        controller.updateState(WebLoadingFailed)
                        Logger.debug(
                            LogLevel.error,
                            LogScope.paywallView,
                            "Webview keeps crashing - paywall ${state.paywall.identifier} not recreated",
                        )
                        if (state.isPresented) {
                            Logger.debug(
                                LogLevel.error,
                                LogScope.paywallView,
                                "Dismissing active paywall due to webview process crash, cannot recreate",
                            )
                            dismiss(
                                PaywallResult.Declined(),
                                PaywallCloseReason.WebViewFailedToLoad,
                            )
                        } else {
                            mainScope.launch {
                                withErrorTracking {
                                    Logger.debug(
                                        LogLevel.error,
                                        LogScope.paywallView,
                                        "Paywall cannot be recreated - cleaning cached instance for next open",
                                    )
                                    beforeOnDestroy()
                                    destroyed()
                                    cleanup()
                                    cache?.removePaywallView(state.paywall.identifier)
                                }
                            }
                        }
                    }
                },
                onLoadFailed = { webViewDidFail() },
            )

            controller.updateState(SetLoadingState(PaywallLoadingState.LoadingURL))
        }
    }

    private fun recreateWebview() {
        val oldWebView = webView
        oldWebView.messageHandler.resetForWebViewReload()
        oldWebView.detach(this)
        oldWebView.destroyView()
        webView =
            (oldWebView as? SWWebView)?.recreate() ?: SWWebView(
                context,
                oldWebView.messageHandler,
                options = { factory.makeSuperwallOptions().paywalls },
                track = { factory.track(it) },
                currentActivity = { encapsulatingActivity?.get() },
                localResources = { emptyMap() },
                deviceLocale = { deviceHelper.locale },
                gameControllerEnabled = { false },
            )
        webView.attach(this)
        webView.delegate = this
        loadWebView()
    }

//endregion

    //region Deep linking

    /** Whether the web checkout sheet opened from this paywall is still open. */
    internal val isPaymentSheetPresented: Boolean
        get() = externalNavigator.isPaymentSheetOpen

    /** Web checkout couldn't be opened or won't report back, so the sheet counts as closed. */
    internal fun paymentSheetNotOpened() = externalNavigator.sheetClosed()

    override fun presentPaymentSheet(url: String) =
        externalNavigator.dispatch(ExternalNavigationState.Actions.OpenPaymentSheet(url))

    override fun presentBrowserInApp(url: String) =
        externalNavigator.dispatch(ExternalNavigationState.Actions.OpenInAppBrowser(url))

    override fun presentBrowserExternal(url: String) =
        externalNavigator.dispatch(ExternalNavigationState.Actions.OpenExternalBrowser(url))

    override fun evaluate(
        code: String,
        resultCallback: ((String?) -> Unit)?,
    ) {
        webView.evaluate(code, resultCallback)
    }

    override fun openDeepLink(url: String) =
        externalNavigator.dispatch(ExternalNavigationState.Actions.OpenDeepLink(url))

    //region GameController
    override fun gameControllerEventOccured(event: GameControllerEvent) {
        val payload =
            try {
                gameControllerJson.encodeToString(event)
            } catch (e: Throwable) {
                null
            }
        webView.evaluate("window.paywall.accept([$payload])", null)
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallView,
            message = "Game controller event occurred: $payload",
        )
    }

//endregion

//region Back button

    /**
     * Forwards a system back press into the paywall (`back_button_input`).
     * The paywall either navigates its flow back one page or posts `close`,
     * which dismisses via the standard manual-close path.
     */
    fun backButtonPressed() {
        webView.messageHandler.handle(PaywallMessage.BackButtonPressed)
    }

//endregion

//region Misc

    // How many presentations the view has been handed to. Whoever shows the view keeps the count
    // it took the view at, and can tell from a higher one that the view has been taken over since,
    // even before the new presentation has put the view in its layout.
    internal var presentationCount = 0
        private set

    // Android-specific
    fun prepareToDisplay() {
        presentationCount++
        // Check if the view already has a parent
        val parentViewGroup = this@PaywallView.parent as? ViewGroup
        parentViewGroup?.removeView(this@PaywallView)
        cache?.activePaywallVcKey = state.cacheKey
    }

    fun cleanup() {
        encapsulatingActivity?.clear()
        callback = null
        webView.onScrollChangeListener = null
        // Clear webView delegate to prevent leak
        webView.delegate = null
        // Unregister activity result launcher to prevent Activity leak
        activityResultLauncher?.unregister()
        activityResultLauncher = null
        launcherOwner = null
        (parent as? ViewGroup)?.removeAllViews()
        removeAllViews()
        detachAllViewsFromParent()
        // Cancel any active state listener to avoid leaks
        stopStateListener()
    }

    internal fun destroyWebview() {
        webView.destroyView()
    }

    private var activityResultLauncher: ActivityResultLauncher<String>? = null
    private var launcherOwner: WeakReference<LifecycleOwner>? = null

    /**
     * Lets go of [activity], which showed the view before another presentation took it over.
     * The activity's teardown leaves a view it no longer shows alone, so the view would otherwise
     * keep its checkout launcher, which no longer works, and its reference to the activity.
     */
    internal fun release(activity: Activity) {
        if (encapsulatingActivity?.get() === activity) {
            encapsulatingActivity = null
        }
        if (launcherOwner?.get() === activity) {
            activityResultLauncher?.unregister()
            activityResultLauncher = null
            launcherOwner = null
        }
    }

    internal fun registerIntent(
        lifecycleOwner: LifecycleOwner,
        registryOwner: ActivityResultRegistryOwner,
    ) {
        launcherOwner = WeakReference(lifecycleOwner)
        activityResultLauncher =
            registryOwner.activityResultRegistry.register(
                "checkoutSheet",
                lifecycleOwner,
                checkoutContract,
            ) { statusCode -> onCheckoutClosed(statusCode) }
    }

    /** Opens web checkout in a Custom Tab, with the tab's result code as its result. */
    internal val checkoutContract =
        object : ActivityResultContract<String, Int>() {
            override fun createIntent(
                context: Context,
                input: String,
            ): Intent = checkoutIntent(context, input)

            override fun parseResult(
                resultCode: Int,
                intent: Intent?,
            ): Int = resultCode
        }

    /** The web checkout Custom Tab: a partial-height sheet styled after the presentation style. */
    internal fun checkoutIntent(
        context: Context,
        url: String,
    ): Intent {
        val height = context.resources.displayMetrics.heightPixels * 0.9
        val customTabsIntent =
            CustomTabsIntent
                .Builder()
                .setColorScheme(COLOR_SCHEME_LIGHT)
                .setDownloadButtonEnabled(false)
                .apply {
                    when (val style = state.presentationStyle) {
                        is PaywallPresentationStyle.Drawer -> {
                            setToolbarCornerRadiusDp(style.cornerRadius.toInt())
                        }

                        is PaywallPresentationStyle.Popup -> {
                            setToolbarCornerRadiusDp(style.cornerRadius.toInt())
                        }

                        else -> {} // NOOP
                    }
                }.setShowTitle(true)
                .setBookmarksButtonEnabled(false)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .setInstantAppsEnabled(true)
                .setSendToExternalDefaultHandlerEnabled(true)
                .setCloseButtonIcon(
                    AppCompatResources
                        .getDrawable(context, R.drawable.none)!!
                        .mutate()
                        .let {
                            DrawableCompat.setTint(it, TRANSPARENT)
                            it.toBitmap()
                        },
                ).setUrlBarHidingEnabled(true)
                .setInitialActivityHeightPx(
                    (height.toInt() * 0.7).toInt(),
                    CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE,
                ).build()
                .intent
        customTabsIntent.setPackage(CustomTabsHelper.getPackageNameToUse(context))
        customTabsIntent.setData(url.toUri())
        return customTabsIntent
    }

    /** The web checkout Custom Tab closed. */
    internal fun onCheckoutClosed(statusCode: Int) {
        externalNavigator.dispatch(ExternalNavigationState.Actions.CheckoutClosed(statusCode))
    }

//endregion
}

interface ActivityEncapsulatable {
    var encapsulatingActivity: WeakReference<Activity>?
}
