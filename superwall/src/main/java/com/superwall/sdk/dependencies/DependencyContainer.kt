package com.superwall.sdk.dependencies

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Base64
import android.webkit.WebSettings
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.android.billingclient.api.Purchase
import com.superwall.sdk.SdkContext
import com.superwall.sdk.SdkContextImpl
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.AttributionManager
import com.superwall.sdk.analytics.ClassifierDataFactory
import com.superwall.sdk.analytics.DefaultClassifierDataFactory
import com.superwall.sdk.analytics.DeviceClassifier
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.attribution.MMPAttributionManager
import com.superwall.sdk.analytics.attribution.MMPAttributionState
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.session.AppManagerDelegate
import com.superwall.sdk.analytics.session.AppSession
import com.superwall.sdk.analytics.session.AppSessionManager
import com.superwall.sdk.billing.GoogleBillingWrapper
import com.superwall.sdk.SdkBootstrapper
import com.superwall.sdk.analytics.SuperwallEventBus
import com.superwall.sdk.config.AppConfigChangeObserver
import com.superwall.sdk.config.AssignmentService
import com.superwall.sdk.config.Assignments
import com.superwall.sdk.identity.SdkResetter
import com.superwall.sdk.models.internal.RedemptionOwnershipType
import com.superwall.sdk.network.device.DeviceOverrides
import com.superwall.sdk.paywall.presentation.internal.confirmAssignment
import com.superwall.sdk.store.ObserverModeHandler
import com.superwall.sdk.store.PurchaseFacade
import com.superwall.sdk.config.ConfigLogic
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.config.PaywallPreload
import com.superwall.sdk.config.options.AdConsentPublisher
import com.superwall.sdk.config.options.AdConsentState
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.config.options.TcfConsentReader
import com.superwall.sdk.customer.CustomerInfoManager
import com.superwall.sdk.customercenter.CustomerCenterManager
import com.superwall.sdk.debug.DebugManager
import com.superwall.sdk.debug.DebugView
import com.superwall.sdk.deeplinks.DeepLinkRouter
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.identity.IdentityContext
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.identity.IdentityPendingInterceptor
import com.superwall.sdk.identity.IdentityPersistenceInterceptor
import com.superwall.sdk.identity.IdentityState
import com.superwall.sdk.identity.createInitialIdentityState
import com.superwall.sdk.identity.cleanAttributes
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.CurrentActivityTracker
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.misc.sha256Hex
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.config.FeatureFlags
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.entitlements.TransactionReceipt
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.models.paywall.LocalNotification
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.DateSerializer
import com.superwall.sdk.network.Api
import com.superwall.sdk.network.BaseHostService
import com.superwall.sdk.network.CollectorService
import com.superwall.sdk.network.EnrichmentService
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.network.MmpService
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.RequestExecutor
import com.superwall.sdk.network.SubscriptionService
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.network.device.DeviceInfo
import com.superwall.sdk.network.device.DeviceIpCollector
import com.superwall.sdk.network.device.DeviceIpState
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import com.superwall.sdk.paywall.manager.PaywallCacheState
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.manager.PaywallViewCache
import com.superwall.sdk.paywall.manager.PaywallViewRegistry
import com.superwall.sdk.paywall.presentation.CustomCallbackRegistry
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PresentationCoordinator
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.paywall.presentation.get_presentation_result.internallyGetPresentationResult
import com.superwall.sdk.paywall.presentation.internal.DefaultGetPaywallComponentsFactory
import com.superwall.sdk.paywall.presentation.internal.GetPaywallComponentsFactory
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.cel.SuperscriptEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.CombinedExpressionEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluating
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.RuleEvaluator
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.request.PaywallRequestManagerDepFactory
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.view.PaywallNotifications
import com.superwall.sdk.paywall.view.PaywallEventHandler
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.SuperwallPaywallActivity
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.SuperwallStoreOwner
import com.superwall.sdk.paywall.view.ViewModelFactory
import com.superwall.sdk.paywall.view.ViewStorageViewModel
import com.superwall.sdk.paywall.view.delegate.PaywallViewDelegateAdapter
import com.superwall.sdk.paywall.view.webview.PaywallResource
import com.superwall.sdk.paywall.view.webview.SWWebView
import com.superwall.sdk.paywall.view.webview.WebviewChecker
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessage
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler
import com.superwall.sdk.paywall.view.webview.templating.models.JsonVariables
import com.superwall.sdk.paywall.view.webview.templating.models.Variables
import com.superwall.sdk.permissions.UserPermissions
import com.superwall.sdk.permissions.UserPermissionsImpl
import com.superwall.sdk.review.MockReviewManager
import com.superwall.sdk.review.ReviewManager
import com.superwall.sdk.review.ReviewManagerImpl
import com.superwall.sdk.storage.DidTrackAppInstall
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.StoredSubscriptionStatus
import com.superwall.sdk.storage.Cache
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.core_data.CoreDataManager
import com.superwall.sdk.store.AutomaticPurchaseController
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.InternalPurchaseController
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.SubscriptionStatusManager
import com.superwall.sdk.store.SubscriptionStatusObserver
import com.superwall.sdk.store.abstractions.product.receipt.ReceiptManager
import com.superwall.sdk.store.abstractions.transactions.GoogleBillingPurchaseTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.defaultGetBilling
import com.superwall.sdk.store.readDeviceEntitlementRecords
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeTransactionHandler
import com.superwall.sdk.store.testmode.ui.TestModeModal
import com.superwall.sdk.store.transactions.TransactionManager
import com.superwall.sdk.store.transactions.notifications.TrialReminderLogic
import com.superwall.sdk.utilities.DateUtils
import com.superwall.sdk.utilities.ErrorTracker
import com.superwall.sdk.utilities.dateFormat
import com.superwall.sdk.utilities.trackError
import com.superwall.sdk.web.DeepLinkReferrer
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.Date
import com.superwall.sdk.paywall.presentation.internal.dismiss as internalDismiss
import com.superwall.sdk.paywall.presentation.internal.internallyPresent

// TransactionVerifierFactory is deprecated for SDK consumers; the SDK still implements it.
@Suppress("DEPRECATION")
class DependencyContainer internal constructor(
    val context: Context,
    purchaseController: PurchaseController?,
    options: SuperwallOptions?,
    var activityProvider: ActivityProvider?,
    val apiKey: String,
    // The facade that owns this container. The tracking pipeline still lives on it, so every
    // `track` the container hands out goes through this reference instead of `Superwall.instance`.
    private val superwall: Superwall?,
) : ApiFactory,
    DeviceInfoFactory,
    AppManagerDelegate,
    RequestFactory,
    RuleAttributesFactory,
    DeviceHelper.Factory,
    CacheFactory,
    PaywallRequestManagerDepFactory,
    VariablesFactory,
    StoreTransactionFactory,
    LocalStorage.Factory,
    InternalSuperwallEvent.PresentationRequest.Factory,
    ViewFactory,
    PaywallManager.Factory,
    OptionsFactory,
    TriggerFactory,
    TransactionVerifierFactory,
    TransactionManager.Factory,
    PaywallView.Factory,
    ConfigManager.Factory,
    AppSessionManager.Factory,
    DebugView.Factory,
    RuleEvaluator.Factory,
    JsonFactory,
    ConfigAttributesFactory,
    PaywallPreload.Factory,
    ViewStoreFactory,
    SuperwallScopeFactory,
    GoogleBillingWrapper.Factory,
    ClassifierDataFactory,
    ExperimentalPropertiesFactory,
    CustomerInfoFactory,
    ActiveEntitlementsFactory,
    WebPaywallRedeemer.Factory {
    @Deprecated("Internal SDK type: the SDK builds its container in Superwall.configure.")
    constructor(
        context: Context,
        purchaseController: PurchaseController? = null,
        options: SuperwallOptions?,
        activityProvider: ActivityProvider?,
        apiKey: String,
    ) : this(context, purchaseController, options, activityProvider, apiKey, superwall = null)

    /** Local paywall resources the app registered through `Superwall.localResources`. */
    @Volatile
    internal var localResources: Map<String, PaywallResource> = emptyMap()

    /** Sets the locale used for paywalls and tracks the changed config attributes. */
    internal fun setLocaleIdentifier(value: String?) {
        configManager.options.localeIdentifier = value
        ioScope.launch {
            trackEvent(makeConfigAttributes())
        }
    }

    /** Dismisses the presented paywall, if one exists, as `Superwall.dismiss()` does. */
    internal suspend fun dismissPaywall() {
        superwall?.dismiss()
    }

    /** Opens the web restore page for the current config in the current activity. */
    internal fun openRestoreOnWeb() =
        com.superwall.sdk.web.openRestoreOnWeb(
            config = configManager.config,
            activityProvider = activityProvider,
        )

    /** The [Superwall] that owns this container, for the public extension functions it calls. */
    internal fun requireOwner(): Superwall =
        superwall ?: throw IllegalStateException("This DependencyContainer is not owned by a configured Superwall.")

    /**
     * Tracks [event] through the owning [Superwall]. Every class the container builds gets this
     * function (or a lambda over it) as its `track` dependency.
     *
     * A container without an owner cannot track: it logs the dropped event, so a wiring miss
     * shows up in the logs instead of only as missing analytics.
     */
    internal suspend fun trackEvent(event: Trackable): Result<TrackingResult> =
        trackThroughOwner(event, superwall?.let { owner -> { owner.track(it) } })

    internal val getPaywallComponentsFactory: GetPaywallComponentsFactory by lazy {
        DefaultGetPaywallComponentsFactory(requireOwner())
    }

    var network: Network
    override var api: Api
    override var deviceHelper: DeviceHelper
    override lateinit var storage: LocalStorage
    override lateinit var configManager: ConfigManager
    override var identityManager: IdentityManager
    override var appLifecycleObserver: AppLifecycleObserver = AppLifecycleObserver()
    var appSessionManager: AppSessionManager
    var sessionEventsManager: SessionEventsManager
    var delegateAdapter: SuperwallDelegateAdapter
    var eventsQueue: EventsQueue
    var debugManager: DebugManager
    var paywallManager: PaywallManager
    var paywallRequestManager: PaywallRequestManager
    var storeManager: StoreManager
    val transactionManager: TransactionManager
    val googleBillingWrapper: GoogleBillingWrapper
    internal val reviewManager: ReviewManager
    internal val userPermissions: UserPermissions
    internal val customCallbackRegistry: CustomCallbackRegistry

    var entitlements: Entitlements
    internal val testMode: TestMode
    internal val testModeTransactionHandler: TestModeTransactionHandler
    internal lateinit var customerInfoManager: CustomerInfoManager

    /** Owns the Customer Center presentation. Built on first use, from the main thread. */
    internal val customerCenterManager: CustomerCenterManager by lazy { CustomerCenterManager(this) }

    /** The activity provider the app passed in, before the container falls back to its own tracker. */
    private val providedActivityProvider: ActivityProvider? = activityProvider

    /** Owns the presented paywall, the last presentation items, the spinner, alerts and notifications. */
    internal val presentationCoordinator: PresentationCoordinator by lazy {
        PresentationCoordinator(
            currentView = { paywallManager.currentView },
            ioScope = ioScope(),
            cancelNotifications = {
                WorkManager
                    .getInstance(context)
                    .cancelAllWorkByTag(SuperwallPaywallActivity.NOTIFICATION_CHANNEL_ID)
            },
        )
    }

    /** Handles the events paywall web views send. Every [PaywallView] made here gets it as its callback. */
    internal val paywallEventHandler: PaywallEventHandler by lazy {
        PaywallEventHandler(
            mainScope = mainScope(),
            ioScope = ioScope(),
            transactionManager = transactionManager,
            delegate = delegateAdapter,
            track = { trackEvent(it) },
            reviewManager = reviewManager,
            deviceHelper = deviceHelper,
            storage = storage,
            notificationFactory = this,
            recheckPreload = { configManager.recheckPreloadIfNeeded() },
            currentActivity = { activityProvider?.getCurrentActivity() },
            providedActivity = { providedActivityProvider?.getCurrentActivity() },
        )
    }
    lateinit var reedemer: WebPaywallRedeemer
    private val uiScope
        get() = mainScope()
    private val ioScope
        get() = ioScope()
    private val evaluator by lazy {
        CombinedExpressionEvaluator(
            storage = storage,
            superscriptEvaluator =
                SuperscriptEvaluator(
                    json =
                        Json(json()) {
                            classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
                            classDiscriminator = "type"
                        },
                    storage = storage.coreDataManager,
                    factory = this,
                    ioScope = ioScope,
                    trackError = { trackError(it) },
                ),
        )
    }

    internal val assignments: Assignments
    private val paywallPreload: PaywallPreload

    internal val errorTracker: ErrorTracker
    internal val deepLinkRouter: DeepLinkRouter
    internal val attributionManager: AttributionManager
    internal val mmpAttributionManager: MMPAttributionManager
    internal val deepLinkReferrer: DeepLinkReferrer

    /** Sends the current ad consent to Superwall as device and config attributes. */
    internal val adConsentPublisher: AdConsentPublisher by lazy {
        AdConsentPublisher(
            scope = ioScope,
            tracker = { trackEvent(it) },
            makeDeviceAttributes = { makeSessionDeviceAttributes() },
            makeConfigAttributes = { makeConfigAttributes() },
            currentAdConsent = { deviceHelper.currentAdConsent },
            eventTrackingBehavior = { makeSuperwallOptions().eventTrackingBehavior },
            updateEventsQueue = { eventsQueue.setTrackingBehavior(it) },
            actor = SequentialActor(AdConsentState(), ioScope),
        )
    }

    /** Sets the subscription status for the app and for the SDK's own purchase handling. */
    internal val subscriptionStatusManager: SubscriptionStatusManager by lazy {
        SubscriptionStatusManager(
            entitlements = { entitlements },
            isTestMode = { testMode.isTestMode },
            hasExternalPurchaseController = { makeHasExternalPurchaseController() },
        )
    }

    /** Tells the delegate, analytics and storage about subscription status and customer info changes. */
    internal val subscriptionStatusObserver: SubscriptionStatusObserver by lazy {
        SubscriptionStatusObserver(
            scope = ioScope,
            subscriptionStatus = entitlements.status,
            customerInfo = customerInfoManager.customerInfo,
            storage = storage,
            delegate = { delegateAdapter },
            track = { trackEvent(it) },
            recheckPreload = { configManager.recheckPreloadIfNeeded() },
        )
    }

    /** Rechecks the paywall preload when the app's locale, timezone or theme changes. */
    internal val appConfigChangeObserver: AppConfigChangeObserver by lazy {
        AppConfigChangeObserver(
            context = context,
            scope = ioScope,
            onChange = { configManager.recheckPreloadIfNeeded() },
        )
    }

    /** Starts the SDK: cached state, listeners, then config and identity. */
    internal val sdkBootstrapper: SdkBootstrapper by lazy {
        SdkBootstrapper(
            scope = ioScope,
            readCachedSubscriptionStatus = { storage.read(StoredSubscriptionStatus) },
            restoreCachedCustomerInfo = { customerInfoManager.restoreCachedCustomerInfo() },
            setSubscriptionStatus = { subscriptionStatusManager.setSubscriptionStatus(it) },
            mergeCustomerInfo = { customerInfoManager.updateMergedCustomerInfo() },
            startListeners = {
                subscriptionStatusObserver.start()
                appConfigChangeObserver.register()
            },
            observeAdConsent = {
                tcfConsentReader.observeReportedChanges(
                    options = { makeSuperwallOptions() },
                    onChange = { adConsentPublisher.publish() },
                )
            },
            shouldMatchInstall = {
                storage.shouldAttemptInitialMMPInstallAttributionMatch(
                    hadTrackedAppInstallBeforeConfigure = storage.read(DidTrackAppInstall) ?: false,
                    appInstalledAtMillis = deviceHelper.appInstalledAtMillis,
                )
            },
            markInstallMatchPending = { mmpAttributionManager.markMatchPending() },
            recordAppInstall = { storage.recordAppInstall { trackEvent(it) } },
            fetchConfiguration = { configManager.fetchConfiguration() },
            configureIdentity = {
                identityManager.configure(neverCalledStaticConfig = storage.neverCalledStaticConfig)
            },
            matchInstall = { mmpAttributionManager.matchInstallOnceEnabled() },
        )
    }

    /** Clears the current user's data on reset and user switches; the identity actor calls it. */
    internal val sdkResetter: SdkResetter by lazy {
        SdkResetter(
            scope = ioScope,
            clearStorage = { storage.reset() },
            clearPaywallCache = { paywallManager.resetCache() },
            clearPresentationItems = { presentationCoordinator.presentationItems.reset() },
            resetConfig = { configManager.reset() },
            clearAppUserRedemptions = { reedemer.clear(RedemptionOwnershipType.AppUser) },
            track = { trackEvent(it) },
        )
    }

    /** Reads and confirms experiment assignments. */
    internal val assignmentService: AssignmentService by lazy {
        AssignmentService(
            track = { trackEvent(it) },
            triggers = { configManager.config?.triggers },
            storedAssignments = { storage.getConfirmedAssignments() },
            confirm = { eventData ->
                val request =
                    makePresentationRequest(
                        presentationInfo = PresentationInfo.ExplicitTrigger(eventData),
                        paywallOverrides = null,
                        isPaywallPresented = false,
                        type = PresentationRequestType.ConfirmAllAssignments,
                    )
                confirmAssignment(getPaywallComponentsFactory, request)
            },
        )
    }

    /** Tracks purchases the app makes itself, when observer mode is on. */
    internal val observerModeHandler: ObserverModeHandler by lazy {
        ObserverModeHandler(
            scope = ioScope,
            shouldObservePurchases = { makeSuperwallOptions().shouldObservePurchases },
            transactionManager = { transactionManager },
        )
    }

    /** Purchase, product, consume and restore calls made outside a paywall. */
    internal val purchaseFacade: PurchaseFacade by lazy {
        PurchaseFacade(
            transactionManager = { transactionManager },
            storeManager = { storeManager },
            hasInternalPurchaseController = { makeHasInternalPurchaseController() },
        )
    }

    /** The platform wrapper and interface style overrides the app sets. */
    internal val deviceOverrides: DeviceOverrides by lazy {
        DeviceOverrides(
            deviceHelper = deviceHelper,
            scope = ioScope,
            trackDeviceAttributes = {
                trackEvent(InternalSuperwallEvent.DeviceAttributes(makeSessionDeviceAttributes()))
            },
            recheckPreload = { configManager.recheckPreloadIfNeeded() },
        )
    }

    /** The flow behind `Superwall.placements`. */
    internal val superwallEventBus: SuperwallEventBus by lazy { SuperwallEventBus(ioScope) }

    /** Ad consent stored by the app's IAB TCF consent banner, in its default SharedPreferences. */
    internal val tcfConsentReader: TcfConsentReader by lazy {
        TcfConsentReader(
            context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE),
        )
    }

    init {
        // For tracking when the app enters the background.
        uiScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        }

        // Always register a lifecycle tracker so we can find the actual foreground activity
        // (e.g. SuperwallPaywallActivity) even when the user provides their own ActivityProvider.
        val currentActivityTracker = CurrentActivityTracker()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            currentActivityTracker,
        )

        val activityProvider: ActivityProvider = this.activityProvider ?: currentActivityTracker
        this.activityProvider = activityProvider

        googleBillingWrapper =
            GoogleBillingWrapper(
                context,
                ioScope,
                appLifecycleObserver = appLifecycleObserver,
                this,
            )
        storage =
            LocalStorage(
                context = context,
                ioScope = ioScope(),
                factory = this,
                json = json(),
                _apiKey = apiKey,
                cache = Cache(context = context, json = json()),
                coreDataManager = CoreDataManager(context = context),
                track = { trackEvent(it) },
            )
        entitlements = Entitlements(storage, actorScope = ioScope)
        val options = options ?: SuperwallOptions()
        testMode =
            TestMode(
                storage = storage,
                getSuperwallProducts = { network.getSuperwallProducts() },
                entitlements = entitlements,
                activityProvider = { this.activityProvider },
                activityTracker = { currentActivityTracker },
                hasExternalPurchaseController = { makeHasExternalPurchaseController() },
                apiKey = { storage.apiKey },
                dashboardBaseUrl = {
                    when (options.networkEnvironment) {
                        is SuperwallOptions.NetworkEnvironment.Developer -> "https://superwall.dev"
                        else -> "https://superwall.com"
                    }
                },
                tracker = { trackEvent(it) },
                showModal = { activity, reason, hasPurchaseController, available, apiKey, dashboardBaseUrl, saved ->
                    TestModeModal.show(
                        activity = activity,
                        reason = reason,
                        hasPurchaseController = hasPurchaseController,
                        availableEntitlements = available,
                        apiKey = apiKey,
                        dashboardBaseUrl = dashboardBaseUrl,
                        savedSettings = saved,
                        userId = { identityManager.userId },
                        userAttributes = { identityManager.userAttributes },
                        deviceAttributes = { makeSessionDeviceAttributes() },
                    )
                },
                ioScope = ioScope,
            )
        testModeTransactionHandler =
            TestModeTransactionHandler(
                testMode = testMode,
                activityProvider = activityProvider,
                activityTracker = currentActivityTracker,
            )

        customerInfoManager =
            CustomerInfoManager(
                storage = storage,
                // The manager holds the merged value itself; nothing else needs telling.
                updateCustomerInfo = {},
                ioScope = ioScope,
                hasExternalPurchaseController = { storeManager.purchaseController.hasExternalPurchaseController },
                getSubscriptionStatus = { entitlements.status.value },
            )

        var purchaseController =
            InternalPurchaseController(
                kotlinPurchaseController =
                    purchaseController
                        ?: AutomaticPurchaseController(
                            context = context,
                            scope = ioScope,
                            entitlementsInfo = { entitlements },
                            deviceEntitlementRecords = { readDeviceEntitlementRecords(storage) },
                            getBilling = defaultGetBilling,
                            passIdentifiersToPlayStore = { makeSuperwallOptions().passIdentifiersToPlayStore },
                            userId = { identityManager.userId },
                            externalAccountId = { identityManager.externalAccountId },
                            configurationState = { configManager.configurationStatus },
                            setSubscriptionStatus = { subscriptionStatusManager.internallySetSubscriptionStatus(it) },
                        ),
                javaPurchaseController = null,
                context,
            )
        storeManager =
            StoreManager(
                purchaseController = purchaseController,
                billing = googleBillingWrapper,
                receiptManagerFactory = {
                    ReceiptManager(
                        delegate = storeManager,
                        billing = googleBillingWrapper,
                        storage = storage,
                        customerInfoManager = { customerInfoManager },
                    )
                },
                getSuperwallProducts = { network.getSuperwallProducts() },
                testMode = testMode,
                track = { trackEvent(it) },
            )

        // Load the Play storefront country once per app start - it can't change
        // without the user switching Play accounts.
        ioScope.launch {
            storeManager.loadStorefrontCountryCode()
        }

        delegateAdapter = SuperwallDelegateAdapter()
        val httpConnection =
            CustomHttpUrlConnection(
                json = json(),
                requestExecutor =
                    RequestExecutor { debugging, requestId ->
                        makeHeaders(debugging, requestId)
                    },
            )

        api = Api(networkEnvironment = options.networkEnvironment)
        network =
            Network(
                baseHostService =
                    BaseHostService(
                        host = api.base.host,
                        Api.version1,
                        factory = this,
                        json = json(),
                        customHttpUrlConnection = httpConnection,
                    ),
                subscriptionService =
                    SubscriptionService(
                        host = api.subscription.host,
                        version = Api.subscriptionsv1,
                        factory = this,
                        json =
                            Json(from = json()) {
                                ignoreUnknownKeys = true
                                namingStrategy = null
                            },
                        customHttpUrlConnection =
                            CustomHttpUrlConnection(
                                json =
                                    Json(from = json()) {
                                        ignoreUnknownKeys = true
                                        namingStrategy = null
                                    },
                                requestExecutor =
                                    RequestExecutor { debugging, requestId ->
                                        makeHeaders(debugging, requestId)
                                    },
                            ),
                    ),
                collectorService =
                    CollectorService(
                        host = api.collector.host,
                        version = Api.version1,
                        factory = this,
                        json = json(),
                        customHttpUrlConnection = httpConnection,
                    ),
                enrichmentService =
                    EnrichmentService(
                        host = api.enrichment.host,
                        version = Api.version1,
                        factory = this,
                        customHttpUrlConnection = httpConnection,
                    ),
                mmpService =
                    MmpService(
                        host = api.mmp.host,
                        version = "/",
                        factory = this,
                        json =
                            Json(from = json()) {
                                ignoreUnknownKeys = true
                                namingStrategy = null
                            },
                        customHttpUrlConnection =
                            CustomHttpUrlConnection(
                                json =
                                    Json(from = json()) {
                                        ignoreUnknownKeys = true
                                        namingStrategy = null
                                        // The backend types `confidence` as a free-form string.
                                        // Coerce an unrecognised value (e.g. a future tier) to the
                                        // property default of `null` rather than failing the whole
                                        // response decode.
                                        coerceInputValues = true
                                    },
                                requestExecutor =
                                    RequestExecutor { debugging, requestId ->
                                        makeHeaders(debugging, requestId)
                                    },
                            ),
                    ),
                factory = this,
            )
        errorTracker = ErrorTracker(scope = ioScope, cache = storage, track = { trackEvent(it) })
        paywallRequestManager =
            PaywallRequestManager(
                storeManager = storeManager,
                network = network,
                factory = this,
                ioScope = ioScope,
                track = { trackEvent(it) },
                getGlobalOverrides = { makeSuperwallOptions().paywalls.overrideProductsByName },
            )
        paywallManager =
            PaywallManager(
                paywallRequestManager = paywallRequestManager,
                factory = this,
            )

        deviceHelper =
            DeviceHelper(
                context = context,
                storage = storage,
                network = network,
                factory = this,
                classifier = DeviceClassifier(DefaultClassifierDataFactory { context }),
                tcfAdConsent = { tcfConsentReader.read() },
                subscriptionStatus = { entitlements.status.value },
                ipCollector =
                    DeviceIpCollector(
                        lookups =
                            DeviceIpCollector.lookups(
                                ipV4Url = options.networkEnvironment.ipV4EnrichmentHost?.let { "https://$it/api/v1/enrich" },
                                ipV6Url = options.networkEnvironment.ipV6EnrichmentHost?.let { "https://$it/api/v1/enrich" },
                            ),
                        scope = ioScope,
                        actor = SequentialActor(DeviceIpState(), ioScope),
                    ),
            )

        assignments =
            Assignments(
                storage = storage,
                network = network,
                ioScope,
            )

        paywallPreload =
            PaywallPreload(
                factory = this,
                storage = storage,
                assignments = assignments,
                paywallManager = paywallManager,
                scope = ioScope,
                track = {
                    trackEvent(it)
                },
            )

        // Identity actor setup
        val initialIdentity = createInitialIdentityState(storage, deviceHelper.appInstalledAtString)
        val identityActor = SequentialActor<IdentityContext, IdentityState>(initialIdentity)

        // DebugInterceptor.install(identityActor, name = "Identity")
        IdentityPendingInterceptor.install(identityActor)
        IdentityPersistenceInterceptor.install(identityActor, storage)

        val sdkContext: SdkContext =
            SdkContextImpl(
                configManager = { configManager },
            )

        // Config actor setup — the SequentialActor serializes all state-mutating
        // actions (fetch, refresh, reset, reevaluate test mode) through a single
        // FIFO queue, so applying a new config can never race with a variant pick.
        val configActor =
            SequentialActor<com.superwall.sdk.config.ConfigContext, ConfigState>(
                ConfigState.None,
                ioScope,
            )
        // DebugInterceptor.install(configActor, name = "Config")

        configManager =
            ConfigManager(
                context = context,
                storeManager = storeManager,
                storage = storage,
                network = network,
                options = options,
                factory = this,
                paywallManager = paywallManager,
                deviceHelper = deviceHelper,
                assignments = assignments,
                ioScope = ioScope,
                paywallPreload = paywallPreload,
                tracker = {
                    trackEvent(it)
                },
                entitlements = entitlements,
                webPaywallRedeemer = { reedemer },
                testMode = testMode,
                identityManager = { identityManager },
                setSubscriptionStatus = { status ->
                    entitlements.setSubscriptionStatus(status)
                },
                actor = configActor,
            )

        identityManager =
            IdentityManager(
                storage = storage,
                options = { options },
                ioScope = ioScope,
                stringToSha = { userId ->
                    checkNotNull(userId.sha256Hex()) { "SHA-256 is unavailable" }
                },
                notifyUserChange = {
                    delegate().userAttributesDidChange(it)
                },
                webPaywallRedeemer = { reedemer },
                completeReset = { sdkResetter.completeReset() },
                installScopedAttributes = { mmpAttributionManager.cachedAcquisitionAttributes() },
                actor = identityActor,
                sdkContext = sdkContext,
            )

        // A single install-referrer client, shared by web-checkout redemption and MMP
        // install attribution — each instance opens its own Play connection.
        deepLinkReferrer = DeepLinkReferrer({ context }, ioScope)

        reedemer =
            WebPaywallRedeemer(
                context = context,
                ioScope = ioScope,
                deepLinkReferrer = deepLinkReferrer,
                network = network,
                storage = storage,
                customerInfoManager = customerInfoManager,
                factory = this,
            )

        eventsQueue =
            EventsQueue(
                context,
                configManager = configManager,
                network = network,
                ioScope = ioScope(),
                mainScope = mainScope(),
            )

        sessionEventsManager =
            SessionEventsManager(
                network = network,
                storage = storage,
                configManager = configManager,
            )

        // Must be after session events
        appSessionManager =
            AppSessionManager(
                storage = storage,
                configManager = configManager,
                delegate = this,
                backgroundScope = ioScope,
                track = { trackEvent(it) },
                republishAdConsentIfChanged = { adConsentPublisher.republishIfChanged() },
            )

        debugManager =
            DebugManager(
                context = context,
                storage = storage,
                factory = this,
                isPaywallPresented = { presentationCoordinator.isPaywallPresented },
                dismissPaywall = { dismissPaywall() },
            )

        uiScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appSessionManager)
        }

        transactionManager =
            TransactionManager(
                storeManager = storeManager,
                purchaseController = purchaseController,
                eventsQueue = eventsQueue,
                storage = storage,
                activityProvider,
                factory = this,
                testMode = testMode,
                testModeTransactionHandler = testModeTransactionHandler,
                setSubscriptionStatus = { status ->
                    entitlements.setSubscriptionStatus(status)
                },
                track = {
                    trackEvent(it)
                },
                dismiss = { key, result ->
                    val paywallView =
                        resolvePaywallViewForKey(
                            makeViewStore(),
                            presentationCoordinator.paywallView,
                            key,
                        )
                    if (paywallView == null) {
                        return@TransactionManager
                    }
                    internalDismiss(paywallView, result)
                },
                ioScope = ioScope(),
                showRestoreDialogForWeb = {
                    showWebRestoreSuccesful()
                },
                notifyBackendOfReceipts = {
                    reedemer.redeem(WebPaywallRedeemer.RedeemType.Existing)
                },
                entitlementsById = {
                    entitlements.byProductId(it)
                },
                allEntitlementsByProductId = {
                    entitlements.entitlementsByProductId
                },
                webEntitlements = {
                    entitlements.web
                },
                subscriptionStatus = {
                    entitlements.status.value
                },
                currentCustomerInfo = {
                    customerInfoManager.customerInfo.value
                },
                activeDeviceEntitlements = {
                    entitlements.activeDeviceEntitlements
                },
                openRestoreOnWeb = {
                    openRestoreOnWeb()
                },
                refreshReceipt = {
                    storeManager.refreshReceipt()
                },
                showAlert = {
                    presentationCoordinator.paywallView?.showAlert(
                        it.title,
                        it.message,
                        it.actionTitle,
                        it.closeActionTitle,
                        it.action,
                        it.onClose,
                    )
                },
                updateState = { key, state ->
                    val paywallView =
                        resolvePaywallViewForKey(
                            makeViewStore(),
                            presentationCoordinator.paywallView,
                            key,
                        )
                    if (paywallView == null) {
                        return@TransactionManager
                    }
                    paywallView.updateState(state)
                },
                notifyOfTransactionAbandon = { key ->
                    val paywallView =
                        resolvePaywallViewForKey(
                            makeViewStore(),
                            presentationCoordinator.paywallView,
                            key,
                        )
                    if (paywallView == null) {
                        return@TransactionManager
                    }
                    paywallView.webView.messageHandler.handle(PaywallMessage.TransactionAbandon)
                },
                notifyOfTransactionComplete = { key, trialEndDate, id, didStartFreeTrial ->
                    val paywallView =
                        resolvePaywallViewForKey(
                            makeViewStore(),
                            presentationCoordinator.paywallView,
                            key,
                        )
                    if (paywallView == null) {
                        Logger.debug(
                            LogLevel.error,
                            LogScope.paywallView,
                            "No active paywall to use - did the app close?",
                        )
                        return@TransactionManager
                    }

                    paywallView.webView.messageHandler.handle(PaywallMessage.TransactionComplete(id))
                    // Schedule fallback notifications from the paywall config in case the paywall
                    // hasn't been updated to send the ScheduleNotification message dynamically.
                    // If the paywall sends a ScheduleNotification message, it will cancel and
                    // replace this notification.
                    // Nothing is scheduled when the purchase did not start a free trial.
                    val paywallInfo = paywallView.state.info
                    val trialNotifications =
                        TrialReminderLogic.fallbackTrialNotifications(
                            paywallInfo = paywallInfo,
                            didStartFreeTrial = didStartFreeTrial,
                        )

                    if (trialNotifications.isNotEmpty()) {

                        val paywallNotifications =
                            PaywallNotifications.forPaywall(
                                paywallView,
                                activityProvider?.getCurrentActivity(),
                            )

                        if (paywallNotifications != null) {
                            ioScope.launch {
                                paywallNotifications.attemptToSchedule(
                                    notifications = trialNotifications,
                                    factory = this@DependencyContainer,
                                    cancelExisting = false,
                                    applySandboxScaling = true,
                                )
                            }
                        } else {
                            Logger.debug(
                                LogLevel.warn,
                                LogScope.paywallView,
                                "No paywall host available to schedule fallback notifications",
                            )
                        }
                    }

                    // Tell the paywall a trial started whenever one did, independent of whether
                    // it has trial reminders configured. This only forwards into the webview, so
                    // it does not double-track the freeTrial_start placement.
                    if (didStartFreeTrial) {
                        // Await message delivery to ensure webview has time to process before dismiss
                        paywallView.webView.messageHandler.handle(
                            PaywallMessage.TrialStarted(
                                trialEndDate,
                                id,
                            ),
                        )
                    }
                },
            )

        reviewManager =
            if (options.useMockReviews) {
                MockReviewManager(context)
            } else {
                ReviewManagerImpl(context, isDebug = {
                    makeIsSandbox()
                })
            }

        userPermissions = UserPermissionsImpl(context)
        customCallbackRegistry = CustomCallbackRegistry()

        deepLinkRouter =
            DeepLinkRouter(
                redeemer = reedemer,
                ioScope = ioScope,
                debugManager = debugManager,
                track = {
                    track(it)
                },
                configurationState = { configManager.configurationStatus },
            )

        attributionManager =
            AttributionManager(storage, {
                track(it)
            }, ioScope = ioScope, redeemAfterSetting = {
                ioScope.launch {
                    reedemer.redeem(WebPaywallRedeemer.RedeemType.IntegrationAttributes)
                }
            }, vendorId = { VendorId(deviceHelper.vendorId) }, startIpCollection = {
                deviceHelper.startIpCollectionIfEnabled()
            })

        mmpAttributionManager =
            MMPAttributionManager(
                storage = storage,
                tracker = { track(it) },
                userAttributes = { identityManager.userAttributes },
                setUserAttributes = { identityManager.mergeUserAttributes(cleanAttributes(it)) },
                sendMatchRequest = { clickId ->
                    network.matchMMPInstall(clickId, attributionManager.integrationAttributes)
                },
                readInstallReferrerClickId = { deepLinkReferrer.checkForMmpClickId().getOrNull() },
                eventTrackingBehavior = { makeSuperwallOptions().eventTrackingBehavior },
                configState = configManager.configState,
                scope = ioScope,
                actor = SequentialActor(MMPAttributionState(), ioScope),
            )

        /**
         * This loads the webview libraries in the background thread, giving us 100-200ms less lag
         * on first webview render.
         * For more info check https://issuetracker.google.com/issues/245155339
         */
        ioScope.launch {
            if (WebviewChecker.webviewExists) {
                // Due to issues with webview's internals approach to loading,
                // We need to catch this and in case of failure, retry
                // as failure is random and time-based
                runCatching {
                    WebSettings.getDefaultUserAgent(context)
                }.onFailure {
                    runCatching {
                        WebSettings.getDefaultUserAgent(context)
                    }
                }
            }
        }
    }

    override suspend fun makeHeaders(
        isForDebugging: Boolean,
        requestId: String,
    ): Map<String, String> {
        // TODO: Add storage
        val key = if (isForDebugging) storage.debugKey else storage.apiKey
        val auth = "Bearer $key"
        val headers =
            mapOf(
                "Authorization" to auth,
                "X-Platform" to "Android",
                "X-Platform-Environment" to "SDK",
                "X-Platform-Wrapper" to deviceHelper.platformWrapper,
                "X-App-User-ID" to (identityManager.appUserId ?: ""),
                "X-Alias-ID" to identityManager.aliasId,
                "X-URL-Scheme" to deviceHelper.urlScheme,
                "X-Vendor-ID" to deviceHelper.vendorId,
                "X-App-Version" to deviceHelper.appVersion,
                "X-OS-Version" to deviceHelper.osVersion,
                "X-Device-Model" to deviceHelper.model,
                "X-Device-Locale" to deviceHelper.locale,
                "X-Device-Language-Code" to deviceHelper.languageCode,
                "X-Device-Currency-Code" to deviceHelper.currencyCode,
                "X-Device-Currency-Symbol" to deviceHelper.currencySymbol,
                "X-Device-Timezone-Offset" to deviceHelper.secondsFromGMT,
                "X-App-Install-Date" to deviceHelper.appInstalledAtString,
                "X-Radio-Type" to deviceHelper.radioType,
                "X-Device-Interface-Style" to deviceHelper.interfaceStyle,
                "X-SDK-Version" to deviceHelper.sdkVersion,
                "X-Git-Sha" to if (deviceHelper.gitSha != null) deviceHelper.gitSha!! else "",
                "X-Build-Time" to if (deviceHelper.buildTime != null) deviceHelper.buildTime!! else "",
                "X-Bundle-ID" to deviceHelper.bundleId,
                "X-Low-Power-Mode" to deviceHelper.isLowPowerModeEnabled.toString(),
                "X-Is-Sandbox" to deviceHelper.isSandbox.toString(),
                "X-Entitlement-Status" to
                    entitlements.status.value
                        .toString(),
                "Content-Type" to "application/json",
                "X-Current-Time" to dateFormat(DateUtils.ISO_MILLIS).format(Date()),
                "X-Static-Config-Build-Id" to (configManager.config?.buildId ?: ""),
            )

        return headers
    }

    private val paywallJson =
        Json {
            encodeDefaults = true
            serializersModule =
                SerializersModule {
                    contextual(Date::class, DateSerializer)
                }
        }

    override suspend fun makePaywallView(
        paywall: Paywall,
        cache: PaywallViewCache?,
        delegate: PaywallViewDelegateAdapter?,
    ): PaywallView {
        val messageHandler =
            PaywallMessageHandler(
                factory = this@DependencyContainer,
                options = this,
                getView = { presentationCoordinator.paywallView },
                track = {
                    trackEvent(it)
                },
                setAttributes = {
                    identityManager.mergeAndNotify(it)
                },
                ioScope = ioScope,
                json = paywallJson,
                mainScope = mainScope(),
                encodeToB64 = {
                    Base64.encodeToString(it.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                },
                userPermissions = userPermissions,
                getActivity = { activityProvider?.getCurrentActivity() },
                customCallbackRegistry = customCallbackRegistry,
            )

        val state =
            PaywallViewState(
                paywall = paywall,
                locale = deviceHelper.locale,
                useMultipleUrls =
                    configManager.config?.featureFlags?.enableMultiplePaywallUrls
                        ?: false,
            )
        val controller = PaywallView.PaywallController(state)
        val paywallView =
            uiScope
                .async {
                    val webView =
                        SWWebView(
                            context = context,
                            messageHandler = messageHandler,
                            options = {
                                makeSuperwallOptions().paywalls
                            },
                            track = { trackEvent(it) },
                        )

                    val paywallView =
                        PaywallView(
                            context = context,
                            factory = this@DependencyContainer,
                            cache = cache,
                            callback = delegate,
                            deviceHelper = deviceHelper,
                            storage = storage,
                            webView = webView,
                            eventCallback = paywallEventHandler,
                            controller = controller,
                            sendMessages = messageHandler,
                            redeemer = reedemer,
                        )
                    webView.delegate = paywallView
                    messageHandler.messageHandler = paywallView
                    paywallView
                }.await()

        return paywallView
    }

    override fun makeDebugView(id: String?): DebugView {
        val view =
            DebugView(
                context = context,
                storeManager = storeManager,
                network = network,
                paywallRequestManager = paywallRequestManager,
                paywallManager = paywallManager,
                debugManager = debugManager,
                factory = this,
                options = { makeSuperwallOptions() },
                setLocaleIdentifier = { setLocaleIdentifier(it) },
                isPaywallPresented = { presentationCoordinator.isPaywallPresented },
                present = { request, publisher -> requireOwner().internallyPresent(request, publisher) },
            )
        view.paywallDatabaseId = id
        // Note: Modal presentation style is an iOS concept. In Android, you might handle this differently.
        return view
    }

    override fun makeCache(): PaywallViewCache =
        PaywallViewCache(
            context,
            makeViewStore(),
            activityProvider!!,
            deviceHelper,
            configManager.options.paywalls.loadingColor,
            actor = SequentialActor(PaywallCacheState(), ioScope),
        )

    override fun activePaywallId(): String? =
        paywallManager.currentView
            ?.state
            ?.paywall
            ?.identifier

    override fun currentCustomerInfo(): CustomerInfo = customerInfoManager.customerInfo.value

    override fun makeDeviceInfo(): DeviceInfo =
        DeviceInfo(
            appInstalledAtString = deviceHelper.appInstalledAtString,
            locale = deviceHelper.locale,
        )

    override fun makeIsSandbox(): Boolean = testMode.isTestMode || deviceHelper.isSandbox

    override suspend fun makeSessionDeviceAttributes(): HashMap<String, Any> {
        val attributes = deviceHelper.getTemplateDevice().toMutableMap()

        attributes.remove("utcDate")
        attributes.remove("localDate")
        attributes.remove("localTime")
        attributes.remove("utcTime")
        attributes.remove("utcDateTime")
        attributes.remove("localDateTime")

        return HashMap(attributes)
    }

    override fun makeUserAttributesEvent(): InternalSuperwallEvent.Attributes =
        InternalSuperwallEvent.Attributes(
            appInstalledAtString = deviceHelper.appInstalledAtString,
            audienceFilterParams = HashMap(identityManager.userAttributes),
        )

    override fun makeHasExternalPurchaseController(): Boolean = storeManager.purchaseController.hasExternalPurchaseController

    override fun makeHasInternalPurchaseController(): Boolean = storeManager.purchaseController.hasInternalPurchaseController

    override fun isWebToAppEnabled(): Boolean = configManager.config?.featureFlags?.web2App ?: false

    override fun restoreUrl(): String = configManager?.config?.webToAppConfig?.restoreAccesUrl ?: ""

    override suspend fun didUpdateAppSession(appSession: AppSession) {
    }

    // Mark - RequestFactory

    // Extension of DependencyContainer implementing RequestFactory
    override fun makePaywallRequest(
        eventData: EventData?,
        responseIdentifiers: ResponseIdentifiers,
        overrides: PaywallRequest.Overrides?,
        isDebuggerLaunched: Boolean,
        presentationSourceType: String?,
    ): PaywallRequest =

        PaywallRequest(
            eventData = eventData,
            responseIdentifiers = responseIdentifiers,
            overrides = overrides ?: PaywallRequest.Overrides(products = null, isFreeTrial = null),
            isDebuggerLaunched = isDebuggerLaunched,
            presentationSourceType = presentationSourceType,
            retryCount = 6,
        )

    override fun makePresentationRequest(
        presentationInfo: PresentationInfo,
        paywallOverrides: PaywallOverrides?,
        presenter: Activity?,
        isDebuggerLaunched: Boolean?,
        subscriptionStatus: StateFlow<SubscriptionStatus?>?,
        isPaywallPresented: Boolean,
        type: PresentationRequestType,
    ): PresentationRequest =
        PresentationRequest(
            presentationInfo = presentationInfo,
            presenter = WeakReference(presenter),
            paywallOverrides = paywallOverrides,
            flags =
                PresentationRequest.Flags(
                    isDebuggerLaunched = isDebuggerLaunched ?: debugManager.isDebuggerLaunched,
                    // TODO: (PresentationCritical) Fix subscription status
                    entitlements = subscriptionStatus ?: entitlements.status,
//                subscriptionStatus = subscriptionStatus!!,
                    isPaywallPresented = isPaywallPresented,
                    type = type,
                ),
        )

    override fun makeStaticPaywall(
        paywallId: String?,
        isDebuggerLaunched: Boolean,
    ): Paywall? {
        if (isDebuggerLaunched) {
            return null
        }
        val deviceInfo = makeDeviceInfo()
        return ConfigLogic.getStaticPaywall(
            withId = paywallId,
            config = configManager.config,
            deviceLocale = deviceInfo.locale,
        )
    }

    override suspend fun makeRuleAttributes(
        event: EventData?,
        computedPropertyRequests: List<ComputedPropertyRequest>,
    ): Map<String, Any> {
        val userAttributes = identityManager.userAttributes.toMutableMap()
        userAttributes.put("isLoggedIn", identityManager.isLoggedIn)

        val deviceAttributes =
            deviceHelper.getDeviceAttributes(
                sinceEvent = event,
                computedPropertyRequests = computedPropertyRequests,
            )

        return mapOf(
            "user" to userAttributes,
            "device" to deviceAttributes,
            "params" to (event?.parameters ?: ""),
        )
    }

    override fun makeFeatureFlags(): FeatureFlags? = configManager.config?.featureFlags

    override fun makeComputedPropertyRequests(): List<ComputedPropertyRequest> = configManager.config?.allComputedProperties ?: emptyList()

    override suspend fun makeIdentityInfo(): IdentityInfo =
        IdentityInfo(
            aliasId = identityManager.aliasId,
            appUserId = identityManager.appUserId,
        )

    override fun makeLocaleIdentifier(): String? = configManager.options?.localeIdentifier

    override suspend fun makeJsonVariables(
        products: List<ProductVariable>?,
        computedPropertyRequests: List<ComputedPropertyRequest>,
        event: EventData?,
    ): JsonVariables {
        val templateDeviceDictionary =
            deviceHelper.getDeviceAttributes(
                sinceEvent = event,
                computedPropertyRequests = computedPropertyRequests,
            )

        return Variables(
            products = products ?: listOf(),
            params = event?.parameters ?: emptyMap(),
            userAttributes = identityManager.userAttributes,
            templateDeviceDictionary = templateDeviceDictionary,
        ).templated()
    }

    override suspend fun makeStoreTransaction(transaction: Purchase): StoreTransaction =
        StoreTransaction(
            GoogleBillingPurchaseTransaction(
                transaction = transaction,
            ),
            configRequestId = configManager.config?.requestId ?: "",
            appSessionId = appSessionManager.appSession.id,
        )

    override suspend fun makeStoreTransaction(
        customTransactionId: String,
        productIdentifier: String,
        purchaseDate: Date,
    ): StoreTransaction =
        StoreTransaction(
            customTransactionId = customTransactionId,
            productIdentifier = productIdentifier,
            purchaseDate = purchaseDate,
            configRequestId = configManager.config?.requestId ?: "",
            appSessionId = appSessionManager.appSession.id,
        )

    override suspend fun activeProductIds(): List<String> = storeManager.receiptManager.purchases.toList()

    override suspend fun makeIdentityManager(): IdentityManager = identityManager

    override fun makeTransactionVerifier(): GoogleBillingWrapper = googleBillingWrapper

    override fun makeSuperwallOptions(): SuperwallOptions = configManager.options

    override suspend fun makeTriggers(): Set<String> = configManager.triggersByEventName.keys

    override suspend fun provideRuleEvaluator(context: Context): ExpressionEvaluating = evaluator

    override fun makeConfigAttributes(): InternalSuperwallEvent.ConfigAttributes =
        InternalSuperwallEvent.ConfigAttributes(
            options = configManager.options,
            hasExternalPurchaseController = makeHasExternalPurchaseController(),
            hasDelegate = delegateAdapter.kotlinDelegate != null || delegateAdapter.javaDelegate != null,
        )

    // Mark - ViewModel management

    private val storeOwner
        get() = SuperwallStoreOwner()
    private val vmFactory
        get() = ViewModelFactory()
    private val vmProvider = ViewModelProvider(storeOwner, vmFactory)

    override fun makeViewStore(): ViewStorageViewModel = vmProvider[ViewStorageViewModel::class.java]

    /**
     * The only way Activities and debug UI should reach paywall views, so the
     * cache and ViewStorage stay in sync. Internal because the registry is.
     */
    internal fun makeViewRegistry(): PaywallViewRegistry = paywallManager.viewRegistry

    private var _mainScope: MainScope? = null
    private var _ioScope: IOScope? = null

    override fun mainScope(): MainScope {
        if (_mainScope == null) {
            _mainScope = MainScope()
        }
        return _mainScope!!
    }

    override fun ioScope(): IOScope {
        if (_ioScope == null) {
            _ioScope = IOScope()
        }
        return _ioScope!!
    }

    private fun showWebRestoreSuccesful() {
        val paywallView: PaywallView? = presentationCoordinator.paywallView

        if (paywallView != null) {
            ioScope.launch {
                trackEvent(
                    InternalSuperwallEvent.Restore(
                        state = InternalSuperwallEvent.Restore.State.Complete,
                        paywallInfo = paywallView.info,
                    ),
                )
                paywallView.handle(PaywallMessage.Restore)
            }
            if (makeSuperwallOptions().paywalls.automaticallyDismiss) {
                ioScope.launch {
                    dismissPaywall()
                }
            }
        }
    }

    private fun trackRestorationFailure(message: String) {
        val paywallView: PaywallView? = presentationCoordinator.paywallView
        val options = makeSuperwallOptions()

        if (paywallView != null) {
            val trackedEvent =
                InternalSuperwallEvent.Restore(
                    InternalSuperwallEvent.Restore.State.Failure(message),
                    paywallView.info,
                )
            ioScope.launch {
                trackEvent(trackedEvent)
            }
            paywallView.handle(PaywallMessage.RestoreFailed(message))
            paywallView.showAlert(
                title = options.paywalls.restoreFailed.title,
                message = options.paywalls.restoreFailed.message,
                closeActionTitle = options.paywalls.restoreFailed.closeButtonTitle,
            )
        }
    }

    override fun context(): Context = context

    override fun experimentalProperties(): Map<String, Any> = storeManager.receiptManager.experimentalProperties()

    override fun getCurrentUserAttributes(): Map<String, Any> = identityManager.userAttributes

    override fun demandTier(): String? = deviceHelper.demandTier

    override fun demandScore(): Int? = deviceHelper.demandScore

    override suspend fun track(event: TrackableSuperwallEvent): Result<TrackingResult> = trackEvent(event)

    override fun delegate(): SuperwallDelegateAdapter = delegateAdapter

    override fun customerInfoFlow(): StateFlow<CustomerInfo> = customerInfoManager.customerInfo

    override fun activeEntitlements(): Set<com.superwall.sdk.models.entitlements.Entitlement> = entitlements.active

    override fun storefrontCountryCode(): String? = storeManager.storefrontCountryCode

    override fun updatePaywallInfo(paywallInfo: PaywallInfo) {
        presentationCoordinator.updatePaywallInfo(paywallInfo)
    }

    override suspend fun storePresentationObject(
        request: PresentationRequest?,
        publisher: MutableSharedFlow<PaywallState>,
    ) {
        presentationCoordinator.storePresentationObjects(request, publisher)
    }

    override suspend fun internallyGetPresentationResult(
        event: Trackable,
        isImplicit: Boolean,
    ) {
        requireOwner().internallyGetPresentationResult(
            event,
            isImplicit,
        )
    }

    // WebPaywallRedeemer.Factory implementation
    override fun willRedeemLink() {
        delegateAdapter.willRedeemLink()
    }

    override fun didRedeemLink(redemptionResult: com.superwall.sdk.models.internal.RedemptionResult) {
        delegateAdapter.didRedeemLink(redemptionResult)
    }

    override fun maxAge(): Long = configManager.config?.webToAppConfig?.entitlementsMaxAgeMs ?: 86400000L

    override fun getActiveDeviceEntitlements(): Set<com.superwall.sdk.models.entitlements.Entitlement> =
        entitlements.activeDeviceEntitlements

    override fun getUserId(): com.superwall.sdk.models.internal.UserId? =
        identityManager.appUserId?.let {
            com.superwall.sdk.models.internal
                .UserId(it)
        }

    override fun getDeviceId(): com.superwall.sdk.models.internal.DeviceVendorId =
        com.superwall.sdk.models.internal
            .DeviceVendorId(
                com.superwall.sdk.models.internal
                    .VendorId(deviceHelper.vendorId),
            )

    override fun getAliasId(): String? = identityManager.aliasId

    override suspend fun track(event: Trackable) {
        trackEvent(event)
    }

    override fun setWebEntitlements(entitlements: Set<Entitlement>) {
        this.entitlements.setWebEntitlements(entitlements)
    }

    override fun internallySetSubscriptionStatus(status: SubscriptionStatus) {
        subscriptionStatusManager.internallySetSubscriptionStatus(status)
    }

    override suspend fun isPaywallVisible(): Boolean = presentationCoordinator.isPaywallPresented

    override suspend fun triggerRestoreInPaywall() {
        showWebRestoreSuccesful()
    }

    override fun currentPaywallEntitlements(): Set<com.superwall.sdk.models.entitlements.Entitlement> =
        presentationCoordinator.paywallView
            ?.state
            ?.paywall
            ?.productIds
            ?.flatMap { entitlements.byProductId(it) }
            ?.toSet() ?: emptySet()

    override fun getPaywallInfo(): PaywallInfo = presentationCoordinator.paywallView?.info ?: PaywallInfo.empty()

    override fun trackRestorationFailed(message: String) {
        trackRestorationFailure(message)
    }

    override suspend fun receipts(): List<TransactionReceipt> =
        googleBillingWrapper
            .queryAllPurchases()
            .map {
                val id = it.products.firstOrNull() ?: return@map null
                val product = storeManager.products(setOf(id)).firstOrNull() ?: return@map null
                TransactionReceipt(
                    it.purchaseToken,
                    it.orderId,
                    id,
                    if (product.rawStoreProduct?.isSubscription == true) {
                        TransactionReceipt.ProductType.SUBSCRIPTION
                    } else {
                        TransactionReceipt.ProductType.IAP
                    },
                )
            }.filterNotNull()

    override fun getExternalAccountId(): String = identityManager.externalAccountId

    override fun getIntegrationProps(): Map<String, Any> {
        val attrs = storage.read(com.superwall.sdk.storage.IntegrationAttributes) ?: emptyMap()
        return attrs.mapKeys { it.key.toString() }
    }

    override fun closePaywallIfExists() {
        ioScope.launch {
            dismissPaywall()
        }
    }

    override suspend fun scheduleTrialNotifications(notifications: List<LocalNotification>) {
        withContext(Dispatchers.Main.immediate) {
            val paywallView = presentationCoordinator.paywallView ?: return@withContext
            val paywallNotifications =
                PaywallNotifications.forPaywall(paywallView, activityProvider?.getCurrentActivity())
                    ?: return@withContext
            val activity = paywallNotifications.activity ?: return@withContext
            if (!activity.isFinishing && !activity.isDestroyed) {
                // Web reminders already use an absolute checkout timestamp, including in sandbox.
                paywallNotifications.attemptToSchedule(
                    notifications,
                    this@DependencyContainer,
                    cancelExisting = false,
                    applySandboxScaling = false,
                )
            }
        }
    }

    override fun isPaymentSheetOpen(): Boolean {
        // TODO: Track payment sheet state
        return false
    }
}

/**
 * Tracks [event] with [track], the owning Superwall's tracker. When there is none, the event is
 * dropped and [logDropped] reports it, so a container that lost its owner shows up in the logs.
 */
internal suspend fun trackThroughOwner(
    event: Trackable,
    track: (suspend (Trackable) -> Result<TrackingResult>)?,
    logDropped: (String) -> Unit = { message ->
        Logger.debug(logLevel = LogLevel.error, scope = LogScope.superwallCore, message = message)
    },
): Result<TrackingResult> {
    if (track == null) {
        logDropped("Dropped event ${event.rawName}: this DependencyContainer has no Superwall owner.")
        return Result.failure(IllegalStateException("This DependencyContainer is not owned by a configured Superwall."))
    }
    return track(event)
}
