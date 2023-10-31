package com.superwall.sdk.dependencies

import ComputedPropertyRequest
import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.Purchase
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.session.AppManagerDelegate
import com.superwall.sdk.analytics.session.AppSession
import com.superwall.sdk.analytics.session.AppSessionManager
import com.superwall.sdk.analytics.trigger_session.TriggerSessionManager
import com.superwall.sdk.config.ConfigLogic
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.ActivityLifecycleTracker
import com.superwall.sdk.misc.VersionHelper
import com.superwall.sdk.misc.sdkVersion
import com.superwall.sdk.models.config.FeatureFlags
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.network.Api
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.network.device.DeviceInfo
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.manager.PaywallViewControllerCache
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.request.PaywallRequestManagerDepFactory
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import com.superwall.sdk.paywall.vc.web_view.SWWebView
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandler
import com.superwall.sdk.paywall.vc.web_view.templating.models.JsonVariables
import com.superwall.sdk.paywall.vc.web_view.templating.models.Variables
import com.superwall.sdk.storage.EventsQueue
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.InternalPurchaseController
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.transactions.GoogleBillingPurchaseTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransactionType
import com.superwall.sdk.store.transactions.GoogleBillingTransactionVerifier
import com.superwall.sdk.store.transactions.TransactionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DependencyContainer(
    val context: Context,
    purchaseController: PurchaseController? = null,
    options: SuperwallOptions?
) : ApiFactory, DeviceInfoFactory, AppManagerDelegate, RequestFactory, TriggerSessionManagerFactory,
    RuleAttributesFactory, DeviceHelper.Factory, CacheFactory,
    PaywallRequestManagerDepFactory, VariablesFactory,
    StoreTransactionFactory, Storage.Factory, InternalSuperwallEvent.PresentationRequest.Factory,
    ViewControllerFactory, PaywallManager.Factory, OptionsFactory, TriggerFactory,
    TransactionVerifierFactory, TransactionManager.Factory, PaywallViewController.Factory,
    ConfigManager.Factory {

    var network: Network
    override lateinit var api: Api
    var deviceHelper: DeviceHelper
    override lateinit var storage: Storage
    override lateinit var configManager: ConfigManager
    override lateinit var identityManager: IdentityManager
    var appSessionManager: AppSessionManager
    var sessionEventsManager: SessionEventsManager
    var delegateAdapter: SuperwallDelegateAdapter
    var queue: EventsQueue
    var paywallManager: PaywallManager
    var paywallRequestManager: PaywallRequestManager
    var storeKitManager: StoreKitManager
    val activityLifecycleTracker: ActivityLifecycleTracker
    val transactionManager: TransactionManager

    init {
        // TODO: Add delegate adapter

        activityLifecycleTracker = ActivityLifecycleTracker()
        // onto
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            activityLifecycleTracker
        )

        var purchaseController = InternalPurchaseController(
            kotlinPurchaseController = purchaseController,
            javaPurchaseController = null,
            context
        )
        storeKitManager = StoreKitManager(context, purchaseController)

        delegateAdapter = SuperwallDelegateAdapter()
        storage = Storage(context = context, factory = this)
        network = Network(factory = this)

        paywallRequestManager = PaywallRequestManager(
            storeKitManager = storeKitManager,
            network = network,
            factory = this
        )
        paywallManager = PaywallManager(
            paywallRequestManager = paywallRequestManager,
            factory = this,
        )

        configManager = ConfigManager(
            storage = storage,
            network = network,
            options = options,
            factory = this,
            paywallManager = paywallManager
        )

        api = Api(networkEnvironment = configManager.options.networkEnvironment)

        deviceHelper = DeviceHelper(context = context, storage = storage, factory = this)

        queue = EventsQueue(context, configManager = configManager, network = network)

        identityManager = IdentityManager(
            storage = storage,
            deviceHelper = deviceHelper,
            configManager = configManager
        )

        sessionEventsManager = SessionEventsManager(
            network = network,
            storage = storage,
            configManager = configManager,
            factory = this
        )

        // Must be after session events
        appSessionManager = AppSessionManager(
            context = context,
            storage = storage,
            configManager = configManager,
            delegate = this
        )

        CoroutineScope(Dispatchers.Main).launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appSessionManager)
        }

        transactionManager = TransactionManager(
            storeKitManager = storeKitManager,
            sessionEventsManager,
            activityLifecycleTracker,
            factory = this
        )
    }


    override suspend fun makeHeaders(
        isForDebugging: Boolean,
        requestId: String
    ): Map<String, String> {
        // TODO: Add storage
        val key = if (isForDebugging) storage.debugKey else storage.apiKey
        val auth = "Bearer $key"
        val headers = mapOf<String, String>(
            "Authorization" to auth,
            "X-Platform" to "Android",
            "X-Platform-Environment" to "SDK",
            "X-App-User-ID" to (identityManager.getAppUserId() ?: ""),
            "X-Alias-ID" to identityManager.getAliasId(),
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
            "X-Git-Sha" to if(deviceHelper.gitSha != null) deviceHelper.gitSha!! else "",
            "X-Build-Time" to  if(deviceHelper.buildTime != null) deviceHelper.buildTime!! else "",
            "X-Bundle-ID" to deviceHelper.bundleId,
            "X-Low-Power-Mode" to deviceHelper.isLowPowerModeEnabled.toString(),
            "X-Is-Sandbox" to deviceHelper.isSandbox.toString(),
            "Content-Type" to "application/json"
        )

        return headers
    }

    override suspend fun makePaywallViewController(
        paywall: Paywall,
        cache: PaywallViewControllerCache?,
        delegate: PaywallViewControllerDelegateAdapter?
    ): PaywallViewController {
        return withContext(Dispatchers.Main) {
            // TODO: Fix this up

            val messageHandler = PaywallMessageHandler(
                sessionEventsManager = sessionEventsManager,
                factory = this@DependencyContainer
            )

            val webViewDeffered = CompletableDeferred<SWWebView>()

            val _webView = SWWebView(
                context = context,
                messageHandler = messageHandler,
                sessionEventsManager = sessionEventsManager,
            )
            webViewDeffered.complete(_webView)

            val webView = webViewDeffered.await()

            val paywallViewController = PaywallViewController(
                context = context,
                paywall = paywall,
                factory = this@DependencyContainer,
                cache = cache,
                delegate = delegate,
                deviceHelper = deviceHelper,
                paywallManager = paywallManager,
                storage = storage,
                webView = webView,
                eventDelegate = Superwall.instance
            )
            webView.delegate = paywallViewController
            messageHandler.delegate = paywallViewController

            return@withContext paywallViewController
        }

    }

    override fun makeCache(): PaywallViewControllerCache {
        return PaywallViewControllerCache(deviceHelper.locale)
    }

    override fun makeDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            appInstalledAtString = deviceHelper.appInstalledAtString,
            locale = deviceHelper.locale,
        )
    }

    override fun makeIsSandbox(): Boolean {
        return deviceHelper.isSandbox
    }

    override fun makeHasExternalPurchaseController(): Boolean {
        return storeKitManager.purchaseController.hasExternalPurchaseController
    }

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
        retryCount: Int
    ): PaywallRequest {
        return PaywallRequest(
            eventData = eventData,
            responseIdentifiers = responseIdentifiers,
            overrides = overrides ?: PaywallRequest.Overrides(products = null, isFreeTrial = null),
            isDebuggerLaunched = isDebuggerLaunched,
            presentationSourceType = presentationSourceType,
            retryCount = retryCount
        )
    }

    override fun makePresentationRequest(
        presentationInfo: PresentationInfo,
        paywallOverrides: PaywallOverrides?,
        presenter: Activity?,
        isDebuggerLaunched: Boolean?,
        subscriptionStatus: StateFlow<SubscriptionStatus?>?,
        isPaywallPresented: Boolean,
        type: PresentationRequestType
    ): PresentationRequest {
        return PresentationRequest(
            presentationInfo = presentationInfo,
            presenter = presenter,
            paywallOverrides = paywallOverrides,
            flags = PresentationRequest.Flags(
                // TODO: (PresentationCritical) debug manager
//                isDebuggerLaunched = isDebuggerLaunched ?: debugManager.isDebuggerLaunched,
                isDebuggerLaunched = isDebuggerLaunched ?: false,
                // TODO: (PresentationCritical) Fix subscription status
                subscriptionStatus = subscriptionStatus ?: Superwall.instance.subscriptionStatus,
//                subscriptionStatus = subscriptionStatus!!,
                isPaywallPresented = isPaywallPresented,
                type = type
            )
        )
    }

    override fun makeTriggerSessionManager(): TriggerSessionManager {
        return TriggerSessionManager(
            delegate = sessionEventsManager,
            sessionEventsManager = sessionEventsManager,
            storage = storage,
            configManager = configManager,
            appSessionManager = appSessionManager,
            identityManager = identityManager
        )
    }

    override fun getTriggerSessionManager(): TriggerSessionManager {
        return sessionEventsManager.triggerSession
    }

    override fun makeStaticPaywall(paywallId: String?): Paywall? {
        val deviceInfo = makeDeviceInfo()
        return ConfigLogic.getStaticPaywall(
            withId = paywallId,
            config = configManager.config,
            deviceLocale = deviceInfo.locale
        )
    }

    override suspend fun makeRuleAttributes(
        event: EventData?,
        computedPropertyRequests: List<ComputedPropertyRequest>
    ): Map<String, Any> {
        val userAttributes = identityManager.getUserAttributes().toMutableMap()
        userAttributes.put("isLoggedIn", identityManager.isLoggedIn)

        val deviceAttributes = deviceHelper.getDeviceAttributes(
            sinceEvent = event,
            computedPropertyRequests = computedPropertyRequests
        )

        return mapOf(
            "user" to userAttributes,
            "device" to deviceAttributes,
            "params" to (event?.parameters ?: "")
        )
    }

    override fun makeFeatureFlags(): FeatureFlags? {
        return configManager.config?.featureFlags
    }

    override fun makeComputedPropertyRequests(): List<ComputedPropertyRequest> {
        return configManager.config?.allComputedProperties ?: emptyList()
    }

    override suspend fun makeIdentityInfo(): IdentityInfo {
        return IdentityInfo(
            aliasId = identityManager.getAliasId(),
            appUserId = identityManager.getAppUserId(),
        )
    }

    override fun makeLocaleIdentifier(): String? {
        return configManager.options?.localeIdentifier
    }

    override suspend fun makeJsonVariables(
        productVariables: List<ProductVariable>?,
        computedPropertyRequests: List<ComputedPropertyRequest>,
        event: EventData?
    ): JsonVariables {
        val templateDeviceDictionary = deviceHelper.getDeviceAttributes(
            sinceEvent = event,
            computedPropertyRequests = computedPropertyRequests
        )

        val variables = Variables(
            productVariables = productVariables ?: listOf<ProductVariable>(),
            params = event?.parameters ?: emptyMap(),
            userAttributes = identityManager.getUserAttributes(),
            templateDeviceDictionary = templateDeviceDictionary
        ).templated()

        return variables
    }

    override suspend fun makeStoreTransaction(transaction: Purchase): StoreTransaction {
        val triggerSessionId =  sessionEventsManager.triggerSession.activeTriggerSession?.first
        return StoreTransaction(
            GoogleBillingPurchaseTransaction(
                transaction = transaction,
            ),
            configRequestId = configManager.config?.requestId ?: "",
            appSessionId = appSessionManager.appSession.id,
            triggerSessionId = triggerSessionId
        )
    }

    override  fun makeTransactionVerifier(): GoogleBillingTransactionVerifier {
        return storeKitManager.purchaseController.transactionVerifier
    }

    override suspend fun makeSuperwallOptions(): SuperwallOptions {
        return configManager.options
    }

    override suspend fun makeTriggers(): Set<String> {
        return configManager.triggersByEventName.keys
    }
}