package com.superwall.sdk

import android.app.Application
import android.content.Context
import android.net.Uri
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.*
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.toConfigurationStatus
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.config.options.AdConsent
import com.superwall.sdk.config.options.AdConsentPublisher
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.customercenter.CustomerCenterConfiguration
import com.superwall.sdk.customercenter.CustomerCenterDelegate
import com.superwall.sdk.deeplinks.DeepLinkRouter
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateJava
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.dependencies.SuperwallGraph
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
import com.superwall.sdk.models.attribution.AttributionProvider
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.network.device.InterfaceStyle
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PresentationCoordinator
import com.superwall.sdk.paywall.presentation.PresentationItems
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult.*
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallViewEventCallback
import com.superwall.sdk.paywall.view.webview.PaywallResource
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource.*
import com.superwall.sdk.utilities.withErrorTracking
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.collections.map

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

    internal val presentationItems: PresentationItems
        get() = presentationCoordinator.presentationItems

    private val presentationCoordinator: PresentationCoordinator
        get() = dependencyContainer.presentationCoordinator

    /**
     * A flow emitting all Superwall placements as an alternative to delegate.
     * @see SuperwallEventInfo for more information and possible placements
     * */

    val placements: SharedFlow<SuperwallEventInfo>
        get() = dependencyContainer.superwallEventBus.placements

    var localeIdentifier: String?
        get() = dependencyContainer.configManager.options.localeIdentifier
        set(value) {
            dependencyContainer.setLocaleIdentifier(value)
        }

    /**
     * Controls which events are sent to the Superwall servers at runtime.
     *
     * Defaults to [EventTrackingBehavior.ALL]. Update this at any point after
     * [configure] to change event collection dynamically — for example, toggling to
     * [EventTrackingBehavior.NONE] after the user declines data collection in a GDPR
     * consent flow.
     *
     * You can also set the initial value via [SuperwallOptions.eventTrackingBehavior]
     * before calling [configure].
     */
    var eventTrackingBehavior: EventTrackingBehavior
        get() = options.eventTrackingBehavior
        set(newValue) {
            val previous = options.eventTrackingBehavior
            options.eventTrackingBehavior = newValue

            mainScope.launch {
                paywallView?.webView?.messageHandler?.passEventTrackingBehaviorToWebView(newValue)
            }

            if (newValue != EventTrackingBehavior.NONE) {
                dependencyContainer.mmpAttributionManager.startMatchIfEnabled()
            }

            adConsentPublisher.trackingBehaviorChanged(previous, newValue)
        }

    /**
     * The user's consent for how their data is used for advertising, which Superwall
     * passes on with the conversions it uploads to Google Ads and Meta.
     *
     * While `adConsent` is [AdConsent.Unspecified], the default, the SDK uses the consent
     * stored by an IAB TCF consent banner when GDPR applies, and otherwise defaults to
     * granted; apps with users in the EEA, UK or Switzerland that don't use a TCF banner
     * should set an [AdConsent.Custom] from their consent flow. Where the values came from
     * is reported in the `adConsentSource` device attribute (`developer`, `tcf` or `default`).
     *
     * Both are reported as denied while [eventTrackingBehavior] is [EventTrackingBehavior.NONE].
     *
     * You can also set the initial value via [SuperwallOptions.adConsent] before calling
     * [configure].
     */
    var adConsent: AdConsent
        get() = options.adConsent
        set(newValue) {
            options.adConsent = newValue
            adConsentPublisher.adConsentChanged()
        }

    /**
     * Re-sends device attributes when the ad consent [sent] carried differs from the current
     * consent. Called after every device attributes send.
     */
    internal fun reconcileAdConsentAfterPublish(sent: Map<String, Any>) {
        adConsentPublisher.reconcileAfterSend(sent)
    }

    /**
     * Re-sends device attributes when the current ad consent differs from the last one sent.
     * Called when the app comes to the foreground, like iOS.
     */
    internal fun republishAdConsentIfChanged() {
        adConsentPublisher.republishIfChanged()
    }

    private val adConsentPublisher: AdConsentPublisher
        get() = dependencyContainer.adConsentPublisher

    /**
     * The presented paywall view.
     */
    val paywallView: PaywallView?
        get() = presentationCoordinator.paywallView

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
        get() = presentationCoordinator.isPaywallPresented

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
     * A map of product name to product identifier that allows you to override products on all paywalls.
     *
     * This is useful when you want to test different products on your paywalls or when you need to
     * override products dynamically. The keys in this map correspond to the product name placeholders
     * in your paywall (e.g., "primary", "secondary", "tertiary"), and the values are the product identifiers.
     *
     * Example:
     * ```
     * Superwall.instance.overrideProductsByName = mapOf(
     *     "primary" to "com.example.premium_monthly",
     *     "tertiary" to "com.example.premium_annual"
     * )
     * ```
     */
    var overrideProductsByName: Map<String, String>
        get() = options.paywalls.overrideProductsByName
        set(value) {
            options.paywalls.overrideProductsByName = value
        }

    /**
     * A mapping of local resource IDs to local paywall resources.
     *
     * Use this to serve paywall assets (images, videos, Lottie animations) from local files
     * instead of fetching them over the network. When a paywall references a `localResourceId`,
     * the SDK will look up the corresponding resource in this map.
     *
     * ```kotlin
     * Superwall.instance.localResources = mapOf(
     *     "hero-video" to PaywallResource.FromUri(Uri.fromFile(File(context.filesDir, "onboarding.mp4"))),
     *     "hero-image" to PaywallResource.FromResources(R.drawable.hero),
     *     "bg-animation" to PaywallResource.FromResources(R.raw.lottie_bg),
     * )
     * ```
     *
     * Paywall HTML usage: `<video src="swlocal://hero-video" autoplay></video>`
     */
    var localResources: Map<String, PaywallResource>
        get() = dependencyContainer.localResources
        set(value) {
            dependencyContainer.localResources = value
        }

    /**
     * Exposes customer info as a stateflow.
     */

    val customerInfo: StateFlow<CustomerInfo> get() = dependencyContainer.customerInfoManager.customerInfo

    /**
     * Gets the current CustomerInfo synchronously.
     *
     * @return The current CustomerInfo containing purchase and subscription data.
     */
    fun getCustomerInfo(): CustomerInfo = customerInfo.value

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
        dependencyContainer.subscriptionStatusManager.setSubscriptionStatus(subscriptionStatus)
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
        dependencyContainer.subscriptionStatusManager.setSubscriptionStatus(entitlements)
    }

    internal fun internallySetSubscriptionStatus(toStatus: SubscriptionStatus) {
        dependencyContainer.subscriptionStatusManager.internallySetSubscriptionStatus(toStatus)
    }

    /**
     * Properties stored about the user, set using `setUserAttributes`.
     */
    val userAttributes: Map<String, Any>
        get() = dependencyContainer.identityManager.userAttributes

    /**
     * Properties stored about the device session, set internally by Superwall
     * */
    suspend fun deviceAttributes(): Map<String, Any?> = dependencyContainer.makeSessionDeviceAttributes()

    /**
     * Gets the current integration identifiers as a map.
     */
    val integrationAttributes: Map<String, String>
        get() =
            dependencyContainer.attributionManager.integrationAttributes

    /**
     * Sets 3rd party integration identifiers for this user.
     * The identifiers will be passed to Superwall backend.
     *
     * @param attributes A map of attribution providers to their respective IDs.
     */

    fun setIntegrationAttributes(attributes: Map<AttributionProvider, String>) {
        dependencyContainer.attributionManager.setIntegrationAttributes(attributes)
    }

    /**
     * The current user's id.
     *
     * If you haven't called `Superwall.identify(userId:options:)`,
     * this value will return an anonymous user id which is cached to disk
     */
    val userId: String
        get() = dependencyContainer.identityManager.userId

    /**
     * The externalAccountId for the current user.
     * Provided to Google Play billing upon purchase as a SHA256 of the userId.
     * If `passIdentifiersToPlayStore` option is provided, this will be the userId.
     */
    val externalAccountId: String
        get() = dependencyContainer.identityManager.externalAccountId

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
        get() = presentationCoordinator.latestPaywallInfo

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
            dependencyContainer.configManager.configState.value
                .toConfigurationStatus()

    val configurationStateListener: Flow<ConfigurationStatus>
        get() =
            dependencyContainer.configManager.configState.map { it.toConfigurationStatus() }

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

        // Retained activity provider for reuse across teardown/configure cycles (hot reload)
        private var retainedActivityProvider: ActivityProvider? = null

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
        @JvmStatic
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

            // Use retained activity provider from previous instance if available (hot reload scenario)
            val effectiveActivityProvider = activityProvider ?: retainedActivityProvider
            retainedActivityProvider = null

            _instance =
                Superwall(
                    context = applicationContext,
                    apiKey = apiKey,
                    purchaseController = purchaseController,
                    options = options,
                    activityProvider = effectiveActivityProvider,
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

        /**
         * Handles a deep link sent to your app to open a preview of your paywall.
         *
         * You can preview your paywall on-device before going live by utilizing paywall previews. This
         * uses a deep link to render a preview of a paywall you've configured on the Superwall dashboard
         * on your device. See [In-App Previews](https://docs.superwall.com/docs/in-app-paywall-previews)
         * for more. Compared to [Superwall.instance.handleDeepLink], this method is safe to call pre-configure.
         *
         * @param uri The URL of the deep link.
         * @return A `Boolean` that is `true` if the deep link was handled.
         */

        fun handleDeepLink(uri: Uri): Result<Boolean> = DeepLinkRouter.handleDeepLink(uri)

        /**
         * Tears down the SDK and prepares it for re-initialization.
         *
         * Call this method in development environments that support hot reload (e.g., Expo, React Native)
         * before calling [configure] again. This method:
         * - Resets all user data, assignments, and caches
         * - Clears the paywall view cache and destroys WebViews
         * - Resets the paywall request cache
         * - Allows [configure] to be called again
         *
         * After calling this method, you must call [configure] to use the SDK again.
         *
         * Example usage in Expo/React Native hot reload:
         * ```kotlin
         * // Before hot reload reconfiguration
         * Superwall.teardown()
         *
         * // Then reconfigure
         * Superwall.configure(applicationContext, apiKey, ...)
         * ```
         *
         * Note: This is intended for development use only. Do not rely on this
         * method in production as a way to reset.
         */
        @JvmStatic
        fun teardown() {
            synchronized(this) {
                // Retain the activity provider for reuse in next configure call
                retainedActivityProvider = _instance?.dependencyContainer?.activityProvider
                _instance?.teardown()
                SuperwallGraph.detach(_instance?.dependencyContainer)
                _instance = null
                initialized = false
                _hasInitialized.update { false }
            }
        }
    }

    @Volatile
    private lateinit var _dependencyContainer: DependencyContainer

    internal val dependencyContainer: DependencyContainer
        get() = _dependencyContainer

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
                            apiKey = apiKey,
                            superwall = this,
                        )
                    SuperwallGraph.attach(dependencyContainer)
                    dependencyContainer.storage.configure(apiKey = apiKey)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }

                dependencyContainer.sdkBootstrapper.start(completion)
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
        presentationCoordinator.togglePaywallSpinner(isHidden)
    }

    /**
     * Do not use this function, this is for internal use only.
     */
    fun setPlatformWrapper(
        wrapper: String,
        version: String,
    ) {
        dependencyContainer.deviceOverrides.setPlatformWrapper(wrapper, version)
    }

    /**
     * Sets the user interface style, which overrides the system setting. Set to `null` to revert
     * back to using the system setting.
     */
    fun setInterfaceStyle(interfaceStyle: InterfaceStyle?) {
        dependencyContainer.deviceOverrides.setInterfaceStyle(interfaceStyle)
    }

    /**
     * Removes all of Superwall's pending local notifications.
     */
    fun cancelAllScheduledNotifications() {
        presentationCoordinator.cancelAllScheduledNotifications()
    }

    /**
     * Shows an alert with provided properties over the paywall UI.
     * Will only show if the current paywall exists/is presented.
     * */
    fun showAlert(
        title: String? = null,
        message: String? = null,
        actionTitle: String? = null,
        closeActionTitle: String = "Done",
        action: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    ) {
        presentationCoordinator.showAlert(
            title,
            message,
            actionTitle,
            closeActionTitle,
            action,
            onClose,
        )
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
     * Coordinates reset through the identity actor. Presentation of paywalls is
     * suspended until reset completes.
     */
    internal fun reset(duringIdentify: Boolean = false) {
        withErrorTracking {
            if (!duringIdentify) {
                // Public reset — delegate to identity actor which coordinates
                // dropping readiness, running cleanup, and restoring readiness.
                dependencyContainer.identityManager.reset()
            } else {
                // Called from identity actor's completeReset during identify
                // or full reset — just do cleanup without touching identity.
                dependencyContainer.sdkResetter.completeReset()
            }
        }
    }

    /**
     * Tears down the SDK and prepares it for re-initialization.
     *
     * Call this method in development environments that support hot reload (e.g., Expo, React Native)
     * before calling [configure] again. This method:
     * - Resets all user data, assignments, and caches
     * - Clears the paywall view cache and destroys WebViews
     * - Resets the paywall request cache
     * - Allows [configure] to be called again
     *
     * After calling this method, you must call [configure] to use the SDK again.
     *
     * Example usage in Expo/React Native hot reload:
     * ```kotlin
     * // Before hot reload reconfiguration
     * Superwall.reset()
     *
     * // Then reconfigure
     * Superwall.configure(applicationContext, apiKey, ...)
     * ```
     *
     * Note: This is intended for development use only.
     */
    internal fun teardown() {
        withErrorTracking {
            // Reset user data and caches
            reset(duringIdentify = false)
            // Also reset the paywall request cache
            dependencyContainer.paywallRequestManager.resetCache()
            // Note: We intentionally do NOT unregister the activity lifecycle callbacks here
            // because the activity provider will be retained and reused in the next configure call.
            // This ensures the current activity is still tracked across hot reload cycles.

            dependencyContainer.appConfigChangeObserver.unregister()
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
     * @deprecated Use the static method [Superwall.handleDeepLink] instead.
     */
    fun handleDeepLink(uri: Uri): Result<Boolean> = DeepLinkRouter.handleDeepLink(uri)
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

    fun getAssignments(): Result<List<ConfirmedAssignment>> = dependencyContainer.assignmentService.getAssignments()

    /**
     * Confirms all experiment assignments and returns them in an array.
     *
     * This tracks ``SuperwallEvent/confirmAllAssignments`` in the delegate.
     *
     * Note that the assignments may be different when a placement is registered due to changes
     * in user, placement, or device parameters used in audience filters.
     * @return An array of `ConfirmedAssignments` objects.
     */

    suspend fun confirmAllAssignments(): Result<List<ConfirmedAssignment>> =
        dependencyContainer.assignmentService.confirmAllAssignments()

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
    suspend fun purchase(product: ProductDetails): Result<PurchaseResult> = dependencyContainer.purchaseFacade.purchase(product)

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
    suspend fun purchase(product: StoreProduct): Result<PurchaseResult> = dependencyContainer.purchaseFacade.purchase(product)

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
    suspend fun purchase(productId: String): Result<PurchaseResult> = dependencyContainer.purchaseFacade.purchase(productId)

    /**
     * Given a list of product identifiers, returns a map of identifiers to `StoreProduct` objects.
     *
     * @param productIds: A list of full product identifiers.
     * @return A map of product identifiers to `StoreProduct` objects.
     */
    suspend fun getProducts(vararg productIds: String): Result<Map<String, StoreProduct>> =
        dependencyContainer.purchaseFacade.getProducts(productIds.toList())

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
     * Initiates a consumption of an In-App product.
     *
     * Use this function to consume an In-App product after processing it in your application.
     * Returns a Result.success if finished, or a Result.failure with a `BillingError`
     * If the error is not a BillingError, something went wrong in communication with the store itself.
     * @param purchaseToken: The `purchaseToken` related to the purchase you wish to consume.
     * @return Result.success with the purchase token or a Result.failure<BillingError>
     */
    suspend fun consume(purchaseToken: String): Result<String> = dependencyContainer.purchaseFacade.consume(purchaseToken)

    /**
     * Initiates a consumption of an In-App product.
     *
     * Use this function to consume an In-App product after processing it in your application.
     * Returns a Result.success if finished, or a Result.failure with a `BillingError`
     * If the error is not a BillingError, something went wrong in communication with the store itself.
     * @param purchaseToken: The `purchaseToken` related to the purchase you wish to consume.
     * @param onConsumed: A callback that will receive the `Result<String>`, containing Result.success with the purchase token or a Result.failure<BillingError>
     */
    fun consume(
        purchaseToken: String,
        onConsumed: (Result<String>) -> Unit,
    ) {
        ioScope.launch {
            onConsumed(
                consume(purchaseToken),
            )
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
        dependencyContainer.observerModeHandler.observe(state)
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
     * Presents the Customer Center, a self-service screen where users can view and manage their
     * subscriptions, request refunds, restore purchases, and contact support.
     *
     * Only one Customer Center can be presented at a time; calling this while one is already
     * presented does nothing. Call it from the main thread.
     *
     * @param configuration Overrides [SuperwallOptions.customerCenter] for this presentation.
     * `null` uses the value configured via [SuperwallOptions].
     * @param delegate Receives Customer Center events. Retained while the Customer Center is
     * presented.
     * @param onDismiss Called after the Customer Center is dismissed.
     */
    @JvmOverloads
    fun presentCustomerCenter(
        configuration: CustomerCenterConfiguration? = null,
        delegate: CustomerCenterDelegate? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        dependencyContainer.customerCenterManager.present(configuration, delegate, onDismiss)
    }

    /**
     * Dismisses a Customer Center presented via [presentCustomerCenter]. Does nothing if none is
     * presented. Call it from the main thread.
     *
     * @param completion Called once the Customer Center has been dismissed.
     */
    @JvmOverloads
    fun dismissCustomerCenter(completion: (() -> Unit)? = null) {
        dependencyContainer.customerCenterManager.dismiss(completion)
    }

    /**
     * Restores purchases
     *
     * Use this function to restore purchases made by the user.
     * */
    suspend fun restorePurchases(): Result<RestorationResult> = dependencyContainer.purchaseFacade.restorePurchases()

    /**
     * Restores purchases and returns the result in a callback.
     *
     * Use this function to restore purchases made by the user.
     * */
    fun restorePurchases(onFinished: (Result<RestorationResult>) -> Unit) =
        ioScope.launch {
            onFinished(restorePurchases())
        }

    /**
     * Handles an event from a paywall's web view. The SDK's own paywalls send their events to
     * the internal handler directly; this stays for views created with `Superwall` as callback.
     */
    override suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallView: PaywallView,
    ) {
        dependencyContainer.paywallEventHandler.eventDidOccur(paywallEvent, paywallView)
    }

    internal fun redeem(code: String) {
        ioScope.launch {
            dependencyContainer.reedemer.redeem(WebPaywallRedeemer.RedeemType.Code(code))
        }
    }

    /**
     * Forces a configuration refresh. Used only for hot reload or explicit testing/debugging cases.
     * Do not use unless explicitly instructed by Superwall dev team.
     */
    fun refreshConfiguration() {
        ioScope.launch {
            dependencyContainer.configManager.refreshConfiguration(force = true)
        }
    }
}
