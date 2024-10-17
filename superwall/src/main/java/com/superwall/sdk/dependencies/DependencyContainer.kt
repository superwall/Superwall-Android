package com.superwall.sdk.dependencies

import android.app.Activity
import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.android.billingclient.api.Purchase
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.session.AppManagerDelegate
import com.superwall.sdk.analytics.session.AppSession
import com.superwall.sdk.analytics.session.AppSessionManager
import com.superwall.sdk.billing.GoogleBillingWrapper
import com.superwall.sdk.config.Assignments
import com.superwall.sdk.config.ConfigLogic
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.PaywallPreload
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.debug.DebugManager
import com.superwall.sdk.debug.DebugView
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.CurrentActivityTracker
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.config.FeatureFlags
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.network.Api
import com.superwall.sdk.network.BaseHostService
import com.superwall.sdk.network.CollectorService
import com.superwall.sdk.network.GeoService
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.RequestExecutor
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.network.device.DeviceInfo
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.manager.PaywallViewCache
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.DefaultJavascriptEvalutor
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.JavascriptEvaluator
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.request.PaywallRequestManagerDepFactory
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.vc.PaywallView
import com.superwall.sdk.paywall.vc.SuperwallStoreOwner
import com.superwall.sdk.paywall.vc.ViewModelFactory
import com.superwall.sdk.paywall.vc.ViewStorageViewModel
import com.superwall.sdk.paywall.vc.delegate.PaywallViewDelegateAdapter
import com.superwall.sdk.paywall.vc.web_view.SWWebView
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandler
import com.superwall.sdk.paywall.vc.web_view.templating.models.JsonVariables
import com.superwall.sdk.paywall.vc.web_view.templating.models.Variables
import com.superwall.sdk.paywall.vc.web_view.webViewExists
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.store.InternalPurchaseController
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.transactions.GoogleBillingPurchaseTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.transactions.TransactionManager
import com.superwall.sdk.utilities.DateUtils
import com.superwall.sdk.utilities.ErrorTracker
import com.superwall.sdk.utilities.dateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Date

class DependencyContainer(
    val context: Context,
    purchaseController: PurchaseController? = null,
    options: SuperwallOptions?,
    var activityProvider: ActivityProvider?,
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
    JavascriptEvaluator.Factory,
    JsonFactory,
    ConfigAttributesFactory,
    PaywallPreload.Factory,
    ViewStoreFactory {
    var network: Network
    override var api: Api
    override var deviceHelper: DeviceHelper
    override lateinit var storage: LocalStorage
    override var configManager: ConfigManager
    override var identityManager: IdentityManager
    override var appLifecycleObserver: AppLifecycleObserver = AppLifecycleObserver()
    var appSessionManager: AppSessionManager
    var sessionEventsManager: SessionEventsManager
    var delegateAdapter: SuperwallDelegateAdapter
    var eventsQueue: EventsQueue
    var debugManager: DebugManager
    var paywallManager: PaywallManager
    var paywallRequestManager: PaywallRequestManager
    var storeKitManager: StoreKitManager
    val transactionManager: TransactionManager
    val googleBillingWrapper: GoogleBillingWrapper
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val evaluator by lazy {
        DefaultJavascriptEvalutor(
            ioScope = ioScope,
            uiScope = uiScope,
            context = context,
            storage = storage,
        )
    }

    internal val assignments: Assignments
    private val paywallPreload: PaywallPreload

    internal val errorTracker: ErrorTracker

    init {
        // TODO: Add delegate adapter

        // For tracking when the app enters the background.
        uiScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        }

        // If activity provider exists, let it be. Otherwise, create our own.
        val activityProvider: ActivityProvider

        if (this.activityProvider == null) {
            val currentActivityTracker = CurrentActivityTracker()

            (context.applicationContext as Application).registerActivityLifecycleCallbacks(
                currentActivityTracker,
            )
            activityProvider = currentActivityTracker
            this.activityProvider = activityProvider
        } else {
            activityProvider = this.activityProvider!!
        }

        googleBillingWrapper =
            GoogleBillingWrapper(context, appLifecycleObserver = appLifecycleObserver)

        var purchaseController =
            InternalPurchaseController(
                kotlinPurchaseController = purchaseController,
                javaPurchaseController = null,
                context,
            )
        storeKitManager = StoreKitManager(context, purchaseController, googleBillingWrapper)

        delegateAdapter = SuperwallDelegateAdapter()
        storage = LocalStorage(context = context, factory = this, json = json())
        val httpConnection =
            CustomHttpUrlConnection(
                json = json(),
                requestExecutor =
                    RequestExecutor { debugging, requestId ->
                        makeHeaders(debugging, requestId)
                    },
            )
        val options = options ?: SuperwallOptions()

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
                collectorService =
                    CollectorService(
                        host = api.collector.host,
                        version = Api.version1,
                        factory = this,
                        json = json(),
                        customHttpUrlConnection = httpConnection,
                    ),
                geoService =
                    GeoService(
                        host = api.geo.host,
                        version = Api.version1,
                        factory = this,
                        customHttpUrlConnection = httpConnection,
                    ),
                factory = this,
            )
        errorTracker = ErrorTracker(scope = ioScope, cache = storage)
        paywallRequestManager =
            PaywallRequestManager(
                storeKitManager = storeKitManager,
                network = network,
                factory = this,
                ioScope = ioScope,
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
            )

        configManager =
            ConfigManager(
                context = context,
                storeKitManager = storeKitManager,
                storage = storage,
                network = network,
                options = options,
                factory = this,
                paywallManager = paywallManager,
                deviceHelper = deviceHelper,
                assignments = assignments,
                ioScope = ioScope,
                paywallPreload =
                paywallPreload,
                track = {
                    Superwall.instance.track(it)
                },
            )

        eventsQueue = EventsQueue(context, configManager = configManager, network = network)

        identityManager =
            IdentityManager(
                storage = storage,
                deviceHelper = deviceHelper,
                configManager = configManager,
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
            )

        debugManager =
            DebugManager(
                context = context,
                storage = storage,
                factory = this,
            )

        uiScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appSessionManager)
        }

        transactionManager =
            TransactionManager(
                storeKitManager = storeKitManager,
                purchaseController = purchaseController,
                sessionEventsManager,
                eventsQueue = eventsQueue,
                activityProvider,
                factory = this,
                context = context,
            )

        /**
         * This loads the webview libraries in the background thread, giving us 100-200ms less lag
         * on first webview render.
         * For more info check https://issuetracker.google.com/issues/245155339
         */
        ioScope.launch {
            if (webViewExists()) {
                WebSettings.getDefaultUserAgent(context)
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
                "X-Subscription-Status" to
                    Superwall.instance.subscriptionStatus.value
                        .toString(),
                "Content-Type" to "application/json",
                "X-Current-Time" to dateFormat(DateUtils.ISO_MILLIS).format(Date()),
                "X-Static-Config-Build-Id" to (configManager.config?.buildId ?: ""),
            )

        return headers
    }

    override suspend fun makePaywallView(
        paywall: Paywall,
        cache: PaywallViewCache?,
        delegate: PaywallViewDelegateAdapter?,
    ): PaywallView {
        val messageHandler =
            PaywallMessageHandler(
                sessionEventsManager = sessionEventsManager,
                factory = this@DependencyContainer,
                ioScope = ioScope,
            )

        val paywallView =
            uiScope
                .async {
                    val webView =
                        SWWebView(
                            context = context,
                            messageHandler = messageHandler,
                        )

                    val paywallView =
                        PaywallView(
                            context = context,
                            paywall = paywall,
                            factory = this@DependencyContainer,
                            cache = cache,
                            callback = delegate,
                            deviceHelper = deviceHelper,
                            storage = storage,
                            webView = webView,
                            eventCallback = Superwall.instance,
                            useMultipleUrls =
                                configManager.config?.featureFlags?.enableMultiplePaywallUrls
                                    ?: false,
                        )
                    webView.delegate = paywallView
                    messageHandler.delegate = paywallView
                    paywallView
                }.await()

        return paywallView
    }

    override fun makeDebugViewController(id: String?): DebugView {
        val view =
            DebugView(
                context = context,
                storeKitManager = storeKitManager,
                network = network,
                paywallRequestManager = paywallRequestManager,
                paywallManager = paywallManager,
                debugManager = debugManager,
                factory = this,
            )
        view.paywallDatabaseId = id
        // Note: Modal presentation style is an iOS concept. In Android, you might handle this differently.
        return view
    }

    override fun makeCache(): PaywallViewCache = PaywallViewCache(context, makeViewStore(), activityProvider!!, deviceHelper)

    override fun makeDeviceInfo(): DeviceInfo =
        DeviceInfo(
            appInstalledAtString = deviceHelper.appInstalledAtString,
            locale = deviceHelper.locale,
        )

    override fun makeIsSandbox(): Boolean = deviceHelper.isSandbox

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

    override fun makeHasExternalPurchaseController(): Boolean = storeKitManager.purchaseController.hasExternalPurchaseController

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
        retryCount: Int,
    ): PaywallRequest =

        PaywallRequest(
            eventData = eventData,
            responseIdentifiers = responseIdentifiers,
            overrides = overrides ?: PaywallRequest.Overrides(products = null, isFreeTrial = null),
            isDebuggerLaunched = isDebuggerLaunched,
            presentationSourceType = presentationSourceType,
            retryCount = retryCount,
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
                    subscriptionStatus = subscriptionStatus ?: Superwall.instance.subscriptionStatus,
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

    override fun makeTransactionVerifier(): GoogleBillingWrapper = googleBillingWrapper

    override suspend fun makeSuperwallOptions(): SuperwallOptions = configManager.options

    override suspend fun makeTriggers(): Set<String> = configManager.triggersByEventName.keys

    override suspend fun provideJavascriptEvaluator(context: Context) = evaluator

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
}
