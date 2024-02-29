package com.superwall.sdk

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.work.WorkManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateJava
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.SerialTaskManager
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PresentationItems
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.SuperwallPaywallActivity
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerEventDelegate
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.*
import com.superwall.sdk.storage.ActiveSubscriptionStatus
import com.superwall.sdk.store.ExternalNativePurchaseController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import java.util.*
import java.util.concurrent.Executors

class Superwall(
    internal var context: Context,
    private var apiKey: String,
    private var purchaseController: PurchaseController?,
    options: SuperwallOptions?,
    private var activityProvider: ActivityProvider?,
    private val completion: (() -> Unit)?
) : PaywallViewControllerEventDelegate {
    private var _options: SuperwallOptions? = options
    // Add a private variable for the purchase task
    private var purchaseTask: Job? = null

    internal val presentationItems: PresentationItems = PresentationItems()

    /**
     * The presented paywall view controller.
     */
    val paywallViewController: PaywallViewController?
        get() = dependencyContainer.paywallManager.presentedViewController

    /**
     * A convenience variable to access and change the paywall options that you passed
     * to [configure].
     */
    val options: SuperwallOptions
        get() = dependencyContainer.configManager.options

    /**
     * Specifies the detail of the logs returned from the SDK to the console.
      */
    var logLevel: LogLevel
        get() = options.logging.level
        set(newValue) {
            options.logging.level = newValue
        }

    /**
     * Determines whether a paywall is being presented.
      */
    val isPaywallPresented: Boolean
        get() = paywallViewController != null

    /**
     * The delegate that handles Superwall lifecycle events.
     */
    var delegate: SuperwallDelegate?
        get() = dependencyContainer.delegateAdapter.kotlinDelegate
        set(newValue) {
            dependencyContainer.delegateAdapter.kotlinDelegate = newValue
        }

    /**
     * Sets the Java delegate that handles Superwall lifecycle events.
     */
    @JvmName("setDelegate")
    fun setJavaDelegate(newValue: SuperwallDelegateJava?) {
        dependencyContainer.delegateAdapter.javaDelegate = newValue
    }

    /**
     * Gets the Java delegate that handles Superwall lifecycle events.
     */
    @JvmName("getDelegate")
    fun getJavaDelegate(): SuperwallDelegateJava? {
        return dependencyContainer.delegateAdapter.javaDelegate
    }


    /** A published property that indicates the subscription status of the user.
     *
     * If you're handling subscription-related logic yourself, you must set this
     * property whenever the subscription status of a user changes.
     *
     * However, if you're letting Superwall handle subscription-related logic, its value will
     * be synced with the user's purchases on device.
     *
     * Paywalls will not show until the subscription status has been established.
     * On first install, it's value will default to [SubscriptionStatus.UNKNOWN]. Afterwards, it'll
     * default to its cached value.
     *
     * You can observe [subscriptionStatus] to get notified whenever the user's subscription status
     * changes.
     *
     * Otherwise, you can check the delegate function
     * [SuperwallDelegate.subscriptionStatusDidChange]
     * to receive a callback with the new value every time it changes.
     *
     * To learn more, see
     * [Purchases and Subscription Status](https://docs.superwall.com/docs/advanced-configuration).
     *
     * @param subscriptionStatus The subscription status of the user.
    */
    fun setSubscriptionStatus(subscriptionStatus: SubscriptionStatus) {
        _subscriptionStatus.value = subscriptionStatus
    }

    /**
     * Properties stored about the user, set using `setUserAttributes`.
      */
    val userAttributes: Map<String, Any>
        get() = dependencyContainer.identityManager.userAttributes

    /**
     * The current user's id.
     *
     * If you haven't called `Superwall.identify(userId:options:)`,
     * this value will return an anonymous user id which is cached to disk
      */
    val userId: String
        get() = dependencyContainer.identityManager.userId

    /**
     * Indicates whether the user is logged in to Superwall.
     *
     * If you have previously called `identify(userId:options:)`, this will
     * return `true`.
    */
    val isLoggedIn: Boolean
        get() = dependencyContainer.identityManager.isLoggedIn

    /**
     * The `PaywallInfo` object of the most recently presented view controller.
      */
    val latestPaywallInfo: PaywallInfo?
        get() {
            val presentedPaywallInfo = dependencyContainer.paywallManager.presentedViewController?.info
            return presentedPaywallInfo ?: presentationItems.paywallInfo
        }

    protected var _subscriptionStatus: MutableStateFlow<SubscriptionStatus> = MutableStateFlow(
        SubscriptionStatus.UNKNOWN
    )

    /**
     * A `StateFlow` of the subscription status of the user. Set this using
     * [setSubscriptionStatus].
     */
    val subscriptionStatus: StateFlow<SubscriptionStatus> get() = _subscriptionStatus

    companion object {
        /** A variable that is only `true` if ``instance`` is available for use.
         * Gets set to `true` immediately after
         * [configure] is called.
         */
        var initialized: Boolean = false

        private val _hasInitialized = MutableStateFlow<Boolean>(false)

        // A flow that emits just once only when `hasInitialized` is non-`nil`.
        val hasInitialized: Flow<Boolean> = _hasInitialized
            .filter { it }
            .take(1)

        lateinit var instance: Superwall

        /**
         * Configures a shared instance of [Superwall] for use throughout your app.
         *
         * Call this as soon as your app finishes launching in `onCreate` in your `MainApplication`
         * class.
         * Check out [Configuring the SDK](https://docs.superwall.com/docs/configuring-the-sdk) for
         * information about how to configure the SDK.
         *
         * @param apiKey Your Public API Key that you can get from the Superwall dashboard
         * settings. If you don't have an account, you can
         * [sign up for free](https://superwall.com/sign-up).
         * @param purchaseController An object that conforms to [PurchaseController]. You must
         * implement this to handle all subscription-related logic yourself. You'll need to also
         * call [setSubscriptionStatus] every time the user's subscription status changes. You can
         * read more about that in
         * [Purchases and Subscription Status](https://docs.superwall.com/docs/advanced-configuration).
         * @param options An optional [SuperwallOptions] object which allows you to customise the
         * appearance and behavior of the paywall.
         *
         * @return The configured [Superwall] instance.
        */
        fun configure(
            applicationContext: Context,
            apiKey: String,
            purchaseController: PurchaseController? = null,
            options: SuperwallOptions? = null,
            activityProvider: ActivityProvider? = null,
            completion: (() -> Unit)? = null
        ) {
            val purchaseController =
                purchaseController ?: ExternalNativePurchaseController(context = applicationContext)
            instance = Superwall(
                context = applicationContext,
                apiKey = apiKey,
                purchaseController = purchaseController,
                options = options,
                activityProvider = activityProvider,
                completion = completion
            )

            instance.setup()

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.superwallCore,
                message = "SDK Version - ${instance.dependencyContainer.deviceHelper.sdkVersion}"
            )

            initialized = true
            // Ping everyone about the initialization
            CoroutineScope(Dispatchers.Main).launch {
                _hasInitialized.emit(true)
            }
        }
    }

    private lateinit var _dependencyContainer: DependencyContainer

    internal val dependencyContainer: DependencyContainer
        get() {
            synchronized(this) {
                return _dependencyContainer
            }
        }

    /// Used to serially execute register calls.
    internal val serialTaskManager = SerialTaskManager()

    internal fun setup() {
        synchronized(this) {
            this._dependencyContainer = DependencyContainer(
                context = context,
                purchaseController = purchaseController,
                options = _options,
                activityProvider = activityProvider
            )
        }

        val cachedSubsStatus = dependencyContainer.storage.get(ActiveSubscriptionStatus) ?: SubscriptionStatus.UNKNOWN
        setSubscriptionStatus(cachedSubsStatus)

        addListeners()

        CoroutineScope(Dispatchers.IO).launch {
            dependencyContainer.storage.configure(apiKey = apiKey)
            dependencyContainer.storage.recordAppInstall {
                track(event = it)
            }
            // Implicitly wait
            dependencyContainer.configManager.fetchConfiguration()
            dependencyContainer.identityManager.configure()

            CoroutineScope(Dispatchers.Main).launch {
                completion?.invoke()
            }
        }
    }

    /// Listens to config and the subscription status
    private fun addListeners() {
        CoroutineScope(Dispatchers.IO).launch {
            subscriptionStatus // Removes duplicates by default
                .drop(1) // Drops the first item
                .collect { newValue -> // Save and handle the new value
                    dependencyContainer.storage.save(newValue, ActiveSubscriptionStatus)
                    dependencyContainer.delegateAdapter.subscriptionStatusDidChange(newValue)
                    val event = InternalSuperwallEvent.SubscriptionStatusDidChange(newValue)
                    track(event)
                }
        }
    }

    /**
     * Toggles the paywall loading spinner on and off.
     *
     * Useful for when you want to display a spinner when doing asynchronous work inside
     * [SuperwallDelegate.handleCustomPaywallAction].
     *
     * @param isHidden Toggles the paywall loading spinner on and off.
     */
    fun togglePaywallSpinner(isHidden: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val paywallViewController = dependencyContainer.paywallManager.presentedViewController ?: return@launch
            paywallViewController.togglePaywallSpinner(isHidden)
        }
    }

    /**
     * Do not use this function, this is for internal use only.
     */
    fun setPlatformWrapper(wrapper: String) {
        dependencyContainer.deviceHelper.platformWrapper = wrapper
    }

    /**
     * Removes all of Superwall's pending local notifications.
     */
    fun cancelAllScheduledNotifications() {
        WorkManager.getInstance(context).cancelAllWorkByTag(SuperwallPaywallActivity.NOTIFICATION_CHANNEL_ID)
    }

    // MARK: - Reset

    /**
     * Resets the [userId], on-device paywall assignments, and data stored by Superwall.
     */
    fun reset() {
        reset(duringIdentify = false)
    }

    /**
     * Asynchronously resets. Presentation of paywalls is suspended until reset completes.
      */
    internal fun reset(duringIdentify: Boolean) {
        dependencyContainer.identityManager.reset(duringIdentify)
        dependencyContainer.storage.reset()
        dependencyContainer.paywallManager.resetCache()
        presentationItems.reset()
        dependencyContainer.configManager.reset()
    }

    //region Deep Links
    /**
     * Handles a deep link sent to your app to open a preview of your paywall.
     *
     * You can preview your paywall on-device before going live by utilizing paywall previews. This
     * uses a deep link to render a preview of a paywall you've configured on the Superwall dashboard
     * on your device. See [In-App Previews](https://docs.superwall.com/docs/in-app-paywall-previews)
     * for more.
     *
     * @param uri The URL of the deep link.
     * @return A `Boolean` that is `true` if the deep link was handled.
     */
    fun handleDeepLink(uri: Uri): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            track(InternalSuperwallEvent.DeepLink(uri = uri))
        }
        return dependencyContainer.debugManager.handle(deepLinkUrl = uri)
    }

    //endregion

    //region Preloading

    /**
     * Preloads all paywalls that the user may see based on campaigns and triggers turned on in your
     * Superwall dashboard.
     *
     * To use this, first set `PaywallOptions/shouldPreload`  to `false` when configuring the SDK.
     * Then call this function when you would like preloading to begin.
     *
     * Note: This will not reload any paywalls you've already preloaded via [preloadPaywalls].
      */
    fun preloadAllPaywalls() {
        CoroutineScope(Dispatchers.IO).launch {
            dependencyContainer.configManager.preloadAllPaywalls()
        }
    }

    /**
     * Preloads paywalls for specific event names.
     *
     * To use this, first set `PaywallOptions/shouldPreload`  to `false` when configuring the SDK.
     * Then call this function when you would like preloading to begin.
     *
     * Note: This will not reload any paywalls you've already preloaded.
     *
     * @param eventNames A set of names of events whose paywalls you want to preload.
     */
    fun preloadPaywalls(eventNames: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            dependencyContainer.configManager.preloadPaywallsByNames(
                eventNames = eventNames
            )
        }
    }
    //endregion

    override suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallViewController: PaywallViewController
    ) {
        withContext(Dispatchers.Main) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallViewController,
                message = "Event Did Occur",
                info = mapOf("event" to paywallEvent)
            )

            when (paywallEvent) {
                is Closed -> {
                    dismiss(
                        paywallViewController,
                        result = PaywallResult.Declined(),
                        closeReason = PaywallCloseReason.ManualClose
                    )
                }
                is InitiatePurchase -> {
                    if (purchaseTask != null) {
                        // If a purchase is already in progress, do not start another
                        return@withContext
                    }
                    purchaseTask = launch {
                        try {
                            dependencyContainer.transactionManager.purchase(
                                paywallEvent.productId,
                                paywallViewController
                            )
                        } finally {
                            // Ensure the task is cleared once the purchase is complete or if an error occurs
                            purchaseTask = null
                        }
                    }
                }
                is InitiateRestore -> {
                    dependencyContainer.transactionManager.tryToRestore(paywallViewController)
                }
                is OpenedURL -> {
                    dependencyContainer.delegateAdapter.paywallWillOpenURL(url = paywallEvent.url)
                }
                is OpenedUrlInSafari -> {
                    dependencyContainer.delegateAdapter.paywallWillOpenURL(url = paywallEvent.url)
                }
                is OpenedDeepLink -> {
                    dependencyContainer.delegateAdapter.paywallWillOpenDeepLink(url = paywallEvent.url)
                }
                is Custom -> {
                    dependencyContainer.delegateAdapter.handleCustomPaywallAction(name = paywallEvent.string)
                }
            }
        }
    }
}

