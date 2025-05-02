package com.superwall.sdk

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.billing.toInternalResult
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateJava
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.misc.SerialTaskManager
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.assignment.ConfirmedAssignment
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.network.device.InterfaceStyle
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PresentationItems
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.confirmAssignment
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.SuperwallPaywallActivity
import com.superwall.sdk.paywall.view.delegate.PaywallViewEventCallback
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.Closed
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.Custom
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.InitiatePurchase
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.InitiateRestore
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.OpenedDeepLink
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.OpenedURL
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent.OpenedUrlInChrome
import com.superwall.sdk.storage.StoredSubscriptionStatus
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

class Superwall(
    context: Context,
    private var apiKey: String,
    private var purchaseController: PurchaseController?,
    options: SuperwallOptions?,
    private var activityProvider: ActivityProvider?,
    private val completion: ((Result<Unit>) -> Unit)?,
) : PaywallViewEventCallback {
    private var _options: SuperwallOptions? = options
    internal val ioScope: IOScope
        get() = dependencyContainer.ioScope()
    internal val mainScope: MainScope
        get() = dependencyContainer.mainScope()
    internal var context: Context = context.applicationContext

    // Add a private variable for the purchase task
    private var purchaseTask: Job? = null

    internal val presentationItems: PresentationItems = PresentationItems()

    private val _placements: MutableSharedFlow<SuperwallEventInfo> =
        MutableSharedFlow(
            0,
            extraBufferCapacity = 64 * 4,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /**
     * A flow emitting all Superwall placements as an alternative to delegate.
     * @see SuperwallEventInfo for more information and possible placements
     * */

    val placements: SharedFlow<SuperwallEventInfo> = _placements

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

    internal fun emitSuperwallEvent(info: SuperwallEventInfo) {
        ioScope.launch {
            _placements.emit(info)
        }
    }

    /**
     * Gets the Java delegate that handles Superwall lifecycle events.
     */
    @JvmName("getDelegate")
    fun getJavaDelegate(): SuperwallDelegateJava? = dependencyContainer.delegateAdapter.javaDelegate

    /**
     * Sets the entitlement status and updates the corresponding entitlement collections.
     *
     * If you're handling subscription-related logic yourself, you must set this
     * property whenever the subscription status of a user changes.
     *
     * However, if you're letting Superwall handle subscription-related logic, its value will
     * be synced with the user's purchases on device.
     *
     * Paywalls will not show until the subscription status has been established.
     * On first install, it's value will default to [SubscriptionStatus.Unknown]. Afterwards, it'll
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
     * @param subscriptionStatus The entitlement status of the user.
     */
    fun setSubscriptionStatus(subscriptionStatus: SubscriptionStatus) {
        entitlements.setSubscriptionStatus(subscriptionStatus)
    }

    /**
     * Simplified version of [Superwall.setSubscriptionStatus] that allows
     * you to set the entitlements by passing in an array of strings.
     * An empty list is treated as [SubscriptionStatus.Inactive].
     * Example:
     * `setSubscriptionStatus("default", "pro")` equals `SubscriptionStatus.Active(setOf(Entitlement("default"), Entitlement("pro")))`
     * `setSubscriptionStatus()` equals `SubscriptionStatus.Inactive`
     *
     * @param entitlements A list of entitlements.
     * */
    fun setSubscriptionStatus(vararg entitlements: String) {
        if (entitlements.isEmpty()) {
            this.entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)
        } else {
            this.setSubscriptionStatus(SubscriptionStatus.Active(entitlements.map { Entitlement(it) }.toSet()))
        }
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

    val entitlements: Entitlements by lazy {
        dependencyContainer.entitlements
    }

    val subscriptionStatus: StateFlow<SubscriptionStatus> by lazy {
        dependencyContainer.entitlements.status
    }

    internal val vendorId: VendorId
        get() = VendorId(dependencyContainer.deviceHelper.vendorId)

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

    val configurationStateListener: Flow<ConfigurationStatus>
        get() =
            dependencyContainer.configManager.configState.asSharedFlow().map {
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
        @JvmOverloads
        fun configure(
            applicationContext: Application,
            apiKey: String,
            purchaseController: PurchaseController? = null,
            options: SuperwallOptions? = null,
            activityProvider: ActivityProvider? = null,
            completion: ((Result<Unit>) -> Unit)? = null,
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
                completion?.invoke(Result.success(Unit))
                return
            }
            _instance =
                Superwall(
                    context = applicationContext,
                    apiKey = apiKey,
                    purchaseController = purchaseController,
                    options = options,
                    activityProvider = activityProvider,
                    completion = completion,
                )

            instance.setup().toResult().fold({
                val sdkVersion = instance.dependencyContainer.deviceHelper.sdkVersion
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.superwallCore,
                    message = "SDK Version - $sdkVersion",
                )
                initialized = true
                // Ping everyone about the initialization
                _hasInitialized.update {
                    true
                }
            }, {
                completion?.invoke(Result.failure(it))
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message = "Superwall SDK failed to initialize - ${it.message}",
                    error = it,
                )
            })
        }
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
        synchronized(this@Superwall) {
            withErrorTracking<Unit> {
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

                val cachedSubscriptionStatus =
                    dependencyContainer.storage.read(StoredSubscriptionStatus)
                        ?: SubscriptionStatus.Unknown
                setSubscriptionStatus(cachedSubscriptionStatus)

                addListeners()

                ioScope.launch {
                    withErrorTracking {
                        dependencyContainer.storage.configure(apiKey = apiKey)
                        dependencyContainer.storage.recordAppInstall {
                            track(event = it)
                        }
                        dependencyContainer.reedemer.checkForRefferal()
                        // Implicitly wait
                        dependencyContainer.configManager.fetchConfiguration()
                        dependencyContainer.identityManager.configure()
                    }.toResult().fold({
                        CoroutineScope(Dispatchers.Main).launch {
                            completion?.invoke(Result.success(Unit))
                        }
                    }, {
                        CoroutineScope(Dispatchers.Main).launch {
                            completion?.invoke(Result.failure(it))
                        }
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.superwallCore,
                            message = "Superwall SDK failed to initialize - ${it.message}",
                            error = it,
                        )
                    })
                }
            }
        }

    // / Listens to config and the subscription status
    private fun addListeners() {
        ioScope.launchWithTracking {
            entitlements.status // Removes duplicates by default
                .drop(1) // Drops the first item
                .collect { newValue ->
                    // Save and handle the new value
                    val oldValue =
                        dependencyContainer.storage.read(StoredSubscriptionStatus)
                            ?: SubscriptionStatus.Unknown
                    dependencyContainer.storage.write(StoredSubscriptionStatus, newValue)
                    dependencyContainer.delegateAdapter.subscriptionStatusDidChange(oldValue, newValue)
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
        ioScope.launchWithTracking {
            val paywallView =
                dependencyContainer.paywallManager.currentView ?: return@launchWithTracking
            paywallView.togglePaywallSpinner(isHidden)
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
        ioScope.launchWithTracking {
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
     * @param placementNames A set of names of events whose paywalls you want to preload.
     */
    fun preloadPaywalls(placementNames: Set<String>) {
        ioScope.launchWithTracking {
            dependencyContainer.configManager.preloadPaywallsByNames(
                eventNames = placementNames,
            )
        }
    }
    //endregion

    /**
     * Gets an array of all confirmed experiment assignments.
     * @return An array of `ConfirmedAssignments` objects.
     * */

    fun getAssignments(): Result<List<ConfirmedAssignment>> =
        withErrorTracking {
            dependencyContainer.storage
                .getConfirmedAssignments()
                .map { ConfirmedAssignment(it.key, it.value) }
        }.toResult()

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
        return withErrorTracking {
            val event = InternalSuperwallEvent.ConfirmAllAssignments
            track(event)
            val triggers =
                dependencyContainer.configManager.config?.triggers
                    ?: return@withErrorTracking emptyList()
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

    /**
     * Initiates a purchase of `ProductDetails`.
     *
     * Use this function to purchase any `ProductDetails`, regardless of whether you
     * have a paywall or not. Superwall will handle the purchase with `GooglePlayBilling`
     * and return the `PurchaseResult`. You'll see the data associated with the
     * purchase on the Superwall dashboard.
     *
     * @param product: The `ProductDetails` you wish to purchase.
     * @return A ``PurchaseResult``.
     * - Note: You do not need to finish the transaction yourself after this.
     * ``Superwall`` will handle this for you.
     */
    suspend fun purchase(product: ProductDetails): Result<PurchaseResult> =
        withErrorTracking {
            dependencyContainer.transactionManager.purchase(
                TransactionManager.PurchaseSource.ExternalPurchase(
                    StoreProduct(RawStoreProduct.from(product)),
                ),
            )
        }.toResult()

    /**
     * Initiates a purchase of `StoreProduct`.
     *
     * Use this function to purchase any `StoreProduct`, regardless of whether you
     * have a paywall or not. Superwall will handle the purchase with `GooglePlayBilling`
     * and return the `PurchaseResult`. You'll see the data associated with the
     * purchase on the Superwall dashboard.
     *
     * @param product: The `StoreProduct` you wish to purchase.
     * @return A ``PurchaseResult``.
     * - Note: You do not need to finish the transaction yourself after this.
     * ``Superwall`` will handle this for you.
     */
    suspend fun purchase(product: StoreProduct): Result<PurchaseResult> =
        withErrorTracking {
            dependencyContainer.transactionManager.purchase(
                TransactionManager.PurchaseSource.ExternalPurchase(
                    product,
                ),
            )
        }.toResult()

    /**
     * Initiates a purchase of a product with the given `productId`.
     *
     * Use this function to purchase any product with a given product ID, regardless of whether you
     * have a paywall or not. Superwall will handle the purchase with `GooglePlayBilling`
     * and return the `PurchaseResult`. You'll see the data associated with the
     * purchase on the Superwall dashboard.
     *
     * @param product: The `produdctId` you wish to purchase.
     * @return A ``PurchaseResult``.
     * - Note: You do not need to finish the transaction yourself after this.
     * ``Superwall`` will handle this for you.
     */
    suspend fun purchase(productId: String): Result<PurchaseResult> =
        withErrorTracking {
            getProducts(productId).getOrThrow()[productId]?.let {
                dependencyContainer.transactionManager.purchase(
                    TransactionManager.PurchaseSource.ExternalPurchase(
                        it,
                    ),
                )
            } ?: throw IllegalArgumentException("Product with id $productId not found")
        }.toResult()

    /**
     * Given a list of product identifiers, returns a map of identifiers to `StoreProduct` objects.
     *
     * @param productIds: A list of full product identifiers.
     * @return A map of product identifiers to `StoreProduct` objects.
     */
    suspend fun getProducts(vararg productIds: String): Result<Map<String, StoreProduct>> =
        withErrorTracking {
            dependencyContainer.storeManager.getProductsWithoutPaywall(productIds.toList())
        }.toResult()

    /**
     * Initiates a purchase of a `StoreProduct` with a callback.
     *
     * Use this function to purchase any `StoreProduct`, regardless of whether you
     * have a paywall or not. Superwall will handle the purchase with `GooglePlayBilling`
     * and return the `PurchaseResult` in `onFinished`. You'll see the data associated with the
     * purchase on the Superwall dashboard.
     *
     * @param product: The `StoreProduct` you wish to purchase.
     * @param onFinished: A callback that will receive the `PurchaseResult`.
     * - Note: You do not need to finish the transaction yourself after this.
     * ``Superwall`` will handle this for you.
     */

    fun purchase(
        product: StoreProduct,
        onFinished: (Result<PurchaseResult>) -> Unit,
    ) {
        ioScope.launch {
            onFinished(purchase(product))
        }
    }

    fun purchase(
        product: ProductDetails,
        onFinished: (Result<PurchaseResult>) -> Unit,
    ) {
        ioScope.launch {
            onFinished(purchase(product))
        }
    }

    fun purchase(
        productId: String,
        onFinished: (Result<PurchaseResult>) -> Unit,
    ) {
        ioScope.launch {
            onFinished(purchase(productId))
        }
    }

    /**
     * Observe purchases made without using Paywalls.
     *
     * This method allows you to track purchases that happen outside of Superwall's paywall flow.
     * It handles different states of the purchase process including start, completion, and errors.
     *
     * Note: The `shouldObservePurchases` option must be enabled in SuperwallOptions for this to work.
     *
     * @param state The current state of the purchase to observe, can be:
     * - PurchaseWillBegin: When a purchase flow is about to start
     * - PurchaseResult: When a purchase completes successfully
     * - PurchaseError: When a purchase fails with an error
     */
    fun observe(state: PurchasingObserverState) {
        ioScope.launchWithTracking {
            if (!options.shouldObservePurchases) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message =
                        "You are trying to observe purchases but the SuperwallOption shouldObservePurchases is " +
                            "false. Please set it to true to be able to observe purchases.",
                )
                return@launchWithTracking
            }
            when (state) {
                is PurchasingObserverState.PurchaseWillBegin -> {
                    val product = StoreProduct(RawStoreProduct.from(state.product))
                    dependencyContainer.transactionManager.prepareToPurchase(
                        product,
                        source = TransactionManager.PurchaseSource.ObserverMode(product),
                    )
                }

                is PurchasingObserverState.PurchaseResult -> {
                    val result = (state.result to state.purchases).toInternalResult()
                    for (internalPurchaseResult in result) {
                        dependencyContainer.transactionManager.handle(
                            internalPurchaseResult,
                            state,
                        )
                    }
                }

                is PurchasingObserverState.PurchaseError -> {
                    dependencyContainer.transactionManager.handle(
                        InternalPurchaseResult.Failed(state.error),
                        state,
                    )
                }
            }
        }
    }

    /**
     * Convenience method to observe when a purchase flow begins.
     *
     * Call this method when a purchase is about to start to track the beginning of the transaction.
     * This will trigger tracking of the Transaction Start event in Superwall's analytics.
     *
     * @param product The Google Play Billing ProductDetails for the product being purchased
     */
    fun observePurchaseStart(product: ProductDetails) {
        observe(PurchasingObserverState.PurchaseWillBegin(product))
    }

    /**
     * Convenience method to observe purchase errors.
     *
     * Call this method when a purchase fails to track the failure in Superwall's analytics.
     * This will trigger tracking of the Transaction Fail event.
     *
     * @param product The Google Play Billing ProductDetails for the product that failed to purchase
     * @param error The error that caused the purchase to fail
     */
    fun observePurchaseError(
        product: ProductDetails,
        error: Throwable,
    ) {
        observe(PurchasingObserverState.PurchaseError(product, error))
    }

    /**
     * Convenience method to observe successful purchases.
     *
     * Call this method when a purchase completes successfully to track the completion in Superwall's analytics.
     * This will trigger tracking of the Transaction Success event.
     *
     * @param billingResult The BillingResult from Google Play Billing containing the purchase response
     * @param purchases List of completed Purchase objects from the transaction
     */
    fun observePurchaseResult(
        billingResult: BillingResult,
        purchases: List<Purchase>,
    ) {
        observe(PurchasingObserverState.PurchaseResult(billingResult, purchases))
    }

    /**
     * Restores purchases
     *
     * Use this function to restore purchases made by the user.
     * */
    suspend fun restorePurchases() =
        withErrorTracking {
            dependencyContainer.transactionManager.tryToRestorePurchases(null)
        }.toResult()

    /**
     * Restores purchases and returns the result in a callback.
     *
     * Use this function to restore purchases made by the user.
     * */
    fun restorePurchases(onFinished: (Result<RestorationResult>) -> Unit) =
        ioScope.launch {
            onFinished(restorePurchases())
        }

    override suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallView: PaywallView,
    ) {
        mainScope.launchWithTracking {
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
                        return@launchWithTracking
                    }
                    purchaseTask =
                        launch {
                            try {
                                dependencyContainer.transactionManager.purchase(
                                    TransactionManager.PurchaseSource.Internal(
                                        paywallEvent.productId,
                                        paywallView,
                                    ),
                                )
                            } finally {
                                // Ensure the task is cleared once the purchase is complete or if an error occurs
                                purchaseTask = null
                            }
                        }
                }

                is InitiateRestore -> {
                    dependencyContainer.transactionManager.tryToRestorePurchases(paywallView)
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
