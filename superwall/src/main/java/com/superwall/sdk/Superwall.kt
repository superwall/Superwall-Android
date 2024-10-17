package com.superwall.sdk

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.ConfigurationStatus
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
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.SerialTaskManager
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.assignment.ConfirmedAssignment
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.network.device.InterfaceStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PresentationItems
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.confirmAssignment
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.SuperwallPaywallActivity
import com.superwall.sdk.paywall.vc.delegate.PaywallViewEventCallback
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.Closed
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.Custom
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.InitiatePurchase
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.InitiateRestore
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.OpenedDeepLink
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.OpenedURL
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.OpenedUrlInChrome
import com.superwall.sdk.storage.ActiveSubscriptionStatus
import com.superwall.sdk.store.ExternalNativePurchaseController
import com.superwall.sdk.utilities.withErrorTracking
import com.superwall.sdk.utilities.withErrorTrackingAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class Superwall(
    context: Context,
    private var apiKey: String,
    private var purchaseController: PurchaseController?,
    options: SuperwallOptions?,
    private var activityProvider: ActivityProvider?,
    private val completion: (() -> Unit)?,
) : PaywallViewEventCallback {
    private var _options: SuperwallOptions? = options
    internal val ioScope = CoroutineScope(Dispatchers.IO)
    internal var context: Context = context.applicationContext

    // Add a private variable for the purchase task
    private var purchaseTask: Job? = null

    internal val presentationItems: PresentationItems = PresentationItems()

    var localeIdentifier: String?
        get() = dependencyContainer.configManager.options.localeIdentifier
        set(value) {
            dependencyContainer.configManager.options.localeIdentifier = value
            ioScope.launch {
                track(dependencyContainer.makeConfigAttributes())
            }
        }

    /**
     * The presented paywall view.
     */
    val paywallView: PaywallView?
        get() = dependencyContainer.paywallManager.currentView

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
            ioScope.launch {
                track(dependencyContainer.makeConfigAttributes())
            }
        }

    /**
     * Determines whether a paywall is being presented.
     */
    val isPaywallPresented: Boolean
        get() = paywallView != null

    /**
     * The delegate that handles Superwall lifecycle events.
     */
    var delegate: SuperwallDelegate?
        get() = dependencyContainer.delegateAdapter.kotlinDelegate
        set(newValue) {
            dependencyContainer.delegateAdapter.kotlinDelegate = newValue
            ioScope.launch {
                track(dependencyContainer.makeConfigAttributes())
            }
        }

    /**
     * Sets the Java delegate that handles Superwall lifecycle events.
     */
    @JvmName("setDelegate")
    fun setJavaDelegate(newValue: SuperwallDelegateJava?) {
        withErrorTracking {
            dependencyContainer.delegateAdapter.javaDelegate = newValue
            ioScope.launch {
                track(dependencyContainer.makeConfigAttributes())
            }
        }
    }

    /**
     * Gets the Java delegate that handles Superwall lifecycle events.
     */
    @JvmName("getDelegate")
    fun getJavaDelegate(): SuperwallDelegateJava? = dependencyContainer.delegateAdapter.javaDelegate

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
     * The `PaywallInfo` object of the most recently presented view.
     */
    val latestPaywallInfo: PaywallInfo?
        get() {
            val presentedPaywallInfo =
                dependencyContainer.paywallManager.currentView?.info
            return presentedPaywallInfo ?: presentationItems.paywallInfo
        }

    protected var _subscriptionStatus: MutableStateFlow<SubscriptionStatus> =
        MutableStateFlow(
            SubscriptionStatus.UNKNOWN,
        )

    /**
     * A `StateFlow` of the subscription status of the user. Set this using
     * [setSubscriptionStatus].
     */
    val subscriptionStatus: StateFlow<SubscriptionStatus> get() = _subscriptionStatus

    /**
     * A property that indicates current configuration state of the SDK.
     *
     * This is `ConfigurationStatus.Pending` when the SDK is yet to finish
     * configuring. Upon successful configuration, it will change to `ConfigurationStatus.Configured`.
     * On failure, it will change to `ConfigurationStatus.Failed`.
     */
    val configurationState: ConfigurationStatus
        get() =
            dependencyContainer.configManager.configState.value.let {
                when (it) {
                    is ConfigState.Retrieved -> ConfigurationStatus.Configured
                    is ConfigState.Failed -> ConfigurationStatus.Failed
                    else -> ConfigurationStatus.Pending
                }
            }

    companion object {
        /** A variable that is only `true` if ``instance`` is available for use.
         * Gets set to `true` immediately after
         * [configure] is called.
         */
        var initialized: Boolean = false

        private val _hasInitialized = MutableStateFlow<Boolean>(false)

        // A flow that emits just once only when `hasInitialized` is non-`nil`.
        val hasInitialized: Flow<Boolean> =
            _hasInitialized
                .filter { it }
                .take(1)

        private var _instance: Superwall? = null
        val instance: Superwall
            get() =
                _instance
                    ?: throw IllegalStateException("Superwall has not been initialized or configured.")

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
            applicationContext: Application,
            apiKey: String,
            purchaseController: PurchaseController? = null,
            options: SuperwallOptions? = null,
            activityProvider: ActivityProvider? = null,
            completion: (() -> Unit)? = null,
        ) {
            if (_hasInitialized.value && _instance == null) {
                _hasInitialized.update { false }
            }
            if (_instance != null) {
                Logger.debug(
                    logLevel = LogLevel.warn,
                    scope = LogScope.superwallCore,
                    message = "Superwall.configure called multiple times. Please make sure you only call this once on app launch.",
                )
                completion?.invoke()
                return
            }
            val purchaseController =
                purchaseController
                    ?: ExternalNativePurchaseController(context = applicationContext)
            _instance =
                Superwall(
                    context = applicationContext,
                    apiKey = apiKey,
                    purchaseController = purchaseController,
                    options = options,
                    activityProvider = activityProvider,
                    completion = completion,
                )

            instance.setup()

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.superwallCore,
                message = "SDK Version - ${instance.dependencyContainer.deviceHelper.sdkVersion}",
            )

            initialized = true
            // Ping everyone about the initialization
            _hasInitialized.update {
                true
            }
        }

        @Deprecated(
            "This constructor is too ambiguous and will be removed in upcoming versions. Use Superwall.configure(Application, ...) instead.",
        )
        fun configure(
            applicationContext: Context,
            apiKey: String,
            purchaseController: PurchaseController? = null,
            options: SuperwallOptions? = null,
            activityProvider: ActivityProvider? = null,
            completion: (() -> Unit)? = null,
        ) = configure(
            applicationContext.applicationContext as Application,
            apiKey,
            purchaseController,
            options,
            activityProvider,
            completion,
        )
    }

    private lateinit var _dependencyContainer: DependencyContainer

    internal val dependencyContainer: DependencyContainer
        get() {
            synchronized(this) {
                return _dependencyContainer
            }
        }

    // / Used to serially execute register calls.
    internal val serialTaskManager = SerialTaskManager()

    internal fun setup(): Either<Unit, Throwable> =
        synchronized(this) {
            withErrorTracking {
                try {
                    _dependencyContainer =
                        DependencyContainer(
                            context = context,
                            purchaseController = purchaseController,
                            options = _options,
                            activityProvider = activityProvider,
                        )
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }

                val cachedSubsStatus =
                    dependencyContainer.storage.read(ActiveSubscriptionStatus)
                        ?: SubscriptionStatus.UNKNOWN
                setSubscriptionStatus(cachedSubsStatus)

                addListeners()

                ioScope.launch {
                    withErrorTrackingAsync {
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
            }
        }

    // / Listens to config and the subscription status
    private fun addListeners() {
        ioScope.launch {
            withErrorTrackingAsync {
                subscriptionStatus // Removes duplicates by default
                    .drop(1) // Drops the first item
                    .collect { newValue ->
                        // Save and handle the new value
                        dependencyContainer.storage.write(ActiveSubscriptionStatus, newValue)
                        dependencyContainer.delegateAdapter.subscriptionStatusDidChange(newValue)
                        val event = InternalSuperwallEvent.SubscriptionStatusDidChange(newValue)
                        track(event)
                    }
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
        ioScope.launch {
            withErrorTracking {
                val paywallView =
                    dependencyContainer.paywallManager.currentView ?: return@withErrorTracking
                paywallView.togglePaywallSpinner(isHidden)
            }
        }
    }

    /**
     * Do not use this function, this is for internal use only.
     */
    fun setPlatformWrapper(
        wrapper: String,
        version: String,
    ) {
        withErrorTracking {
            dependencyContainer.deviceHelper.platformWrapper = wrapper
            dependencyContainer.deviceHelper.platformWrapperVersion = version
            ioScope.launch {
                track(InternalSuperwallEvent.DeviceAttributes(dependencyContainer.makeSessionDeviceAttributes()))
            }
        }
    }

    /**
     * Sets the user interface style, which overrides the system setting. Set to `null` to revert
     * back to using the system setting.
     */
    fun setInterfaceStyle(interfaceStyle: InterfaceStyle?) {
        withErrorTracking {
            dependencyContainer.deviceHelper.interfaceStyleOverride = interfaceStyle
            ioScope.launch {
                track(InternalSuperwallEvent.DeviceAttributes(dependencyContainer.makeSessionDeviceAttributes()))
            }
        }
    }

    /**
     * Removes all of Superwall's pending local notifications.
     */
    fun cancelAllScheduledNotifications() {
        withErrorTracking {
            WorkManager
                .getInstance(context)
                .cancelAllWorkByTag(SuperwallPaywallActivity.NOTIFICATION_CHANNEL_ID)
        }
    }

    // MARK: - Reset

    /**
     * Resets the [userId], on-device paywall assignments, and data stored by Superwall.
     */
    fun reset() {
        withErrorTracking {
            reset(duringIdentify = false)
        }
    }

    /**
     * Asynchronously resets. Presentation of paywalls is suspended until reset completes.
     */
    internal fun reset(duringIdentify: Boolean) {
        withErrorTracking {
            dependencyContainer.identityManager.reset(duringIdentify)
            dependencyContainer.storage.reset()
            dependencyContainer.paywallManager.resetCache()
            presentationItems.reset()
            dependencyContainer.configManager.reset()
            ioScope.launch {
                track(InternalSuperwallEvent.Reset)
            }
        }
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
    fun handleDeepLink(uri: Uri): Result<Boolean> =
        withErrorTracking<Boolean> {
            ioScope.launch {
                track(InternalSuperwallEvent.DeepLink(uri = uri))
            }
            dependencyContainer.debugManager.handle(deepLinkUrl = uri)
        }.toResult()

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
        ioScope.launch {
            withErrorTrackingAsync {
                dependencyContainer.configManager.preloadAllPaywalls()
            }
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
        ioScope.launch {
            withErrorTrackingAsync {
                dependencyContainer.configManager.preloadPaywallsByNames(
                    eventNames = eventNames,
                )
            }
        }
    }
    //endregion

    /**
     * Gets an array of all confirmed experiment assignments.
     * @return An array of `ConfirmedAssignments` objects.
     * */

    fun getAssignments(): List<ConfirmedAssignment> =
        dependencyContainer.storage
            .getConfirmedAssignments()
            .map { ConfirmedAssignment(it.key, it.value) }

    /**
     * Confirms all experiment assignments and returns them in an array.
     *
     * This tracks ``SuperwallEvent/confirmAllAssignments`` in the delegate.
     *
     * Note that the assignments may be different when a placement is registered due to changes
     * in user, placement, or device parameters used in audience filters.
     * @return An array of `ConfirmedAssignments` objects.
     */

    suspend fun confirmAllAssignments(): Result<List<ConfirmedAssignment>> {
        return withErrorTrackingAsync {
            val event = InternalSuperwallEvent.ConfirmAllAssignments
            track(event)
            val triggers =
                dependencyContainer.configManager.config?.triggers
                    ?: return@withErrorTrackingAsync emptyList()
            val storedAssignments = dependencyContainer.storage.getConfirmedAssignments()
            val assignments =
                storedAssignments
                    .map {
                        ConfirmedAssignment(it.key, it.value)
                    }.toMutableSet()

            triggers.forEach {
                val eventData =
                    EventData(name = it.eventName, parameters = emptyMap(), createdAt = Date())
                val request =
                    dependencyContainer.makePresentationRequest(
                        presentationInfo = PresentationInfo.ExplicitTrigger(eventData),
                        paywallOverrides = null,
                        isPaywallPresented = false,
                        type = PresentationRequestType.ConfirmAllAssignments,
                    )
                confirmAssignment(request).fold(
                    onSuccess = {
                        it?.let {
                            assignments.add(it)
                        }
                    },
                ) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.superwallCore,
                        message = "Failed to confirm assignment",
                        error = it,
                    )
                }
            }
            assignments.toList()
        }.toResult()
    }

    /**
     * Confirms all experiment assignments and returns them in an array.
     *
     * This tracks ``SuperwallEvent/ConfirmAllAssignments`` in the delegate.
     *
     * Note that the assignments may be different when a placement is registered due to changes
     * in user, placement, or device parameters used in audience filters.
     * @param callback callback that will receive the confirmed assignments.
     */
    fun Superwall.confirmAllAssignments(callback: (Result<List<ConfirmedAssignment>>) -> Unit) {
        ioScope.launch {
            val assignments = confirmAllAssignments()
            callback(assignments)
        }
    }

    override suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallView: PaywallView,
    ) {
        withContext(Dispatchers.Main) {
            withErrorTrackingAsync {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallView,
                    message = "Event Did Occur",
                    info = mapOf("event" to paywallEvent),
                )

                when (paywallEvent) {
                    is Closed -> {
                        dismiss(
                            paywallView,
                            result = PaywallResult.Declined(),
                            closeReason = PaywallCloseReason.ManualClose,
                        )
                    }

                    is InitiatePurchase -> {
                        if (purchaseTask != null) {
                            // If a purchase is already in progress, do not start another
                            return@withErrorTrackingAsync
                        }
                        purchaseTask =
                            launch {
                                try {
                                    dependencyContainer.transactionManager.purchase(
                                        paywallEvent.productId,
                                        paywallView,
                                    )
                                } finally {
                                    // Ensure the task is cleared once the purchase is complete or if an error occurs
                                    purchaseTask = null
                                }
                            }
                    }

                    is InitiateRestore -> {
                        dependencyContainer.transactionManager.tryToRestore(paywallView)
                    }

                    is OpenedURL -> {
                        dependencyContainer.delegateAdapter.paywallWillOpenURL(url = paywallEvent.url)
                    }

                    is OpenedUrlInChrome -> {
                        dependencyContainer.delegateAdapter.paywallWillOpenURL(url = paywallEvent.url)
                    }

                    is OpenedDeepLink -> {
                        dependencyContainer.delegateAdapter.paywallWillOpenDeepLink(url = paywallEvent.url)
                    }

                    is Custom -> {
                        dependencyContainer.delegateAdapter.handleCustomPaywallAction(name = paywallEvent.string)
                    }

                    is PaywallWebEvent.CustomPlacement -> {
                        track(
                            InternalSuperwallEvent.CustomPlacement(
                                placementName = paywallEvent.name,
                                params =
                                    paywallEvent.params.let {
                                        val map = mutableMapOf<String, Any>()
                                        for (key in it.keys()) {
                                            map[key] = it.get(key)
                                        }
                                        map
                                    },
                                paywallInfo = paywallView.info,
                            ),
                        )
                    }
                }
            }
        }
    }
}
