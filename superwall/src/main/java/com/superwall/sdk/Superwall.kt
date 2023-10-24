package com.superwall.sdk

import LogLevel
import LogScope
import Logger
import android.content.Context
import android.net.Uri
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.billing.BillingController
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateJava
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.misc.SerialTaskManager
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PresentationItems
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerEventDelegate
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallWebEvent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.*

class Superwall(
    context: Context,
    apiKey: String,
    purchaseController: PurchaseController?,
    options: SuperwallOptions?
) :
    PaywallViewControllerEventDelegate {
    private var apiKey: String = apiKey
    internal var context: Context = context
    private var purchaseController: PurchaseController? = purchaseController
    private var _options: SuperwallOptions? = options

    private var billingController = BillingController(context)

    internal val presentationItems: PresentationItems = PresentationItems()

    /**
     * The presented paywall view controller.
     */
    val paywallViewController: PaywallViewController?
        get() = dependencyContainer.paywallManager.presentedViewController

    /**
     * A convenience variable to access and change the paywall options that you passed
     * to `configure(apiKey:purchaseController:options:)`.
     */
    val options: SuperwallOptions
        get() = dependencyContainer.configManager.options

    /// Determines whether a paywall is being presented.
    val isPaywallPresented: Boolean
        get() = paywallViewController != null

    var delegate: SuperwallDelegate?
        get() = dependencyContainer.delegateAdapter.kotlinDelegate
        set(newValue) {
            dependencyContainer.delegateAdapter.kotlinDelegate = newValue
        }

    @JvmName("setDelegate")
    fun setJavaDelegate(newValue: SuperwallDelegateJava?) {
        dependencyContainer.delegateAdapter.javaDelegate = newValue
    }

    @JvmName("getDelegate")
    fun getJavaDelegate(): SuperwallDelegateJava? {
        return dependencyContainer.delegateAdapter.javaDelegate
    }


    /// A published property that indicates the subscription status of the user.
    ///
    /// If you're handling subscription-related logic yourself, you must set this
    /// property whenever the subscription status of a user changes.
    /// However, if you're letting Superwall handle subscription-related logic, its value will
    /// be synced with the user's purchases on device.
    ///
    /// Paywalls will not show until the subscription status has been established.
    /// On first install, it's value will default to `.unknown`. Afterwards, it'll default
    /// to its cached value.
    ///
    /// If you're using Combine or SwiftUI, you can subscribe or bind to it to get
    /// notified whenever the user's subscription status changes.
    ///
    /// Otherwise, you can check the delegate function
    /// ``SuperwallDelegate/subscriptionStatusDidChange(to:)-24teh``
    /// to receive a callback with the new value every time it changes.
    ///
    /// To learn more, see [Purchases and Subscription Status](https://docs.superwall.com/docs/advanced-configuration).
    fun setSubscriptionStatus(subscriptionStatus: SubscriptionStatus) {
        _subscriptionStatus.value = subscriptionStatus
    }

    /// Properties stored about the user, set using `setUserAttributes`.
    suspend fun getUserAttributes(): Map<String, Any> {
        return dependencyContainer.identityManager.getUserAttributes()
    }

    protected var _subscriptionStatus: MutableStateFlow<SubscriptionStatus> = MutableStateFlow(
        SubscriptionStatus.UNKNOWN
    )
    val subscriptionStatus: StateFlow<SubscriptionStatus> get() = _subscriptionStatus


    companion object {
        var initialized: Boolean = false
        lateinit var instance: Superwall

        /** Configures a shared instance of `Superwall` for use throughout your app.
         *
         * Call this as soon as your app finishes launching in `application(_:didFinishLaunchingWithOptions:)`.
         * Check out [Configuring the SDK](https://docs.superwall.com/docs/configuring-the-sdk) for information about
         * how to configure the SDK.
         *
         * Parameters:
         *   - `apiKey`: Your Public API Key that you can get from the Superwall dashboard settings. If you don't have
         *   an account, you can [sign up for free](https://superwall.com/sign-up).
         *   - `purchaseController`: An object that conforms to ``PurchaseController``. You must implement this to
         *   handle all subscription-related logic yourself. You'll need to also set the ``subscriptionStatus`` every time the user's
         *   subscription status changes. You can read more about that in [Purchases and Subscription Status](https://docs.superwall.com/docs/advanced-configuration).
         *   - options: An optional ``SuperwallOptions`` object which allows you to customise the appearance and behavior
         *   of the paywall.
         * Returns: The configured ``Superwall`` instance.
        */
        fun configure(
            applicationContext: Context,
            apiKey: String,
            purchaseController: PurchaseController,
            options: SuperwallOptions? = null,
        ) {
            // setup the SDK using that API Key
            instance = Superwall(applicationContext, apiKey, purchaseController, options)
            instance.setup()
            initialized = true
        }
    }


    internal lateinit var dependencyContainer: DependencyContainer

    /// Used to serially execute register calls.
    internal val serialTaskManager = SerialTaskManager()

    internal fun setup() {
        this.dependencyContainer = DependencyContainer(context, purchaseController, _options)

        CoroutineScope(Dispatchers.IO).launch {
            dependencyContainer.storage.configure(apiKey = apiKey)
            dependencyContainer.storage.recordAppInstall {
                track(event = it)
            }
            // Implicitly wait
            dependencyContainer.configManager.fetchConfiguration()
            dependencyContainer.identityManager.configure()
//
//                await MainActor.run {
//                    completion?()
//                }
        }
//
//        private convenience init(
//            apiKey: String,
//            swiftPurchaseController: PurchaseController? = nil,
//        objcPurchaseController: PurchaseControllerObjc? = nil,
//        options: SuperwallOptions? = nil,
//        completion: (() -> Void)?
//        ) {
//            let dependencyContainer = DependencyContainer(
//                    swiftPurchaseController: swiftPurchaseController,
//            objcPurchaseController: objcPurchaseController,
//            options: options
//            )
//            self.init(dependencyContainer: dependencyContainer)
//
//            subscriptionStatus = dependencyContainer.storage.get(ActiveSubscriptionStatus.self) ?? .unknown
//
//            addListeners()
//
//            // This task runs on a background thread, even if called from a main thread.
//            // This is because the function isn't marked to run on the main thread,
//            // therefore, we don't need to make this detached.
//            Task {
//                dependencyContainer.storage.configure(apiKey: apiKey)
//
//                dependencyContainer.storage.recordAppInstall()
//
//                await dependencyContainer.configManager.fetchConfiguration()
//                await dependencyContainer.identityManager.configure()
//
//                await MainActor.run {
//                    completion?()
//                }
//            }
//        }
//
//        Logger.debug(LogLevel.warn, LogScope.cache, "Hello")

        // Called after the constructor to begin setting up the sdk
//        Log.println(Log.INFO, "Superwall", "Superwall setup")

//        // Make sure we start tacking the contexts so we know where to present the paywall
//        // onto
//        (contex.applicationContext as Application).registerActivityLifecycleCallbacks(
//            ActivityLifecycleTracker.instance)
//
//
//
//        val scope = CoroutineScope(Dispatchers.IO)
//
//        val self = this
//        scope.launch {
//            self.dependencyContainer.network.getConfig()
//        }

        // Fetch the static configuration from the server
//        Network.getStaticConfig {
//            config ->
//            Log.println(Log.INFO, "Superwall", "Superwall config: " + config)
//            if (config != null) {
//                // Save the config
//                this.config = config
//                postConfig()
//                Log.println(Log.INFO, "Superwall", "Superwall config" + config)
//            }
//        }
    }

//    protected var config: Config? = null
//
//    protected  var productMap: Map<String, SkuDetails> = mapOf()

//    private fun postConfig() {
//        // Post the config to the webview
//        Log.println(Log.INFO, "Superwall", "Superwall post config")
//
//        // Load all the products using google play billing
//        val productIds = config!!.paywalls.flatMap {
//            paywall -> paywall.products.map { product -> product.id }
//        }
//        billingController.querySkuDetails(productIds as ArrayList<String>) {
//            skuDetails ->
//            Log.println(Log.INFO, "Superwall", "Superwall skuDetails: " + skuDetails)
//            if (skuDetails != null) {
//                // Save the skuDetails
//                Log.println(Log.INFO, "Superwall", "Superwall skuDetails" + skuDetails)
//                this.productMap = skuDetails.map { skuDetail -> skuDetail.sku to skuDetail }.toMap()
//
//            }
//        }
//
//
//
//
//    }

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


    //    // MARK: - Reset

    /**
     * Resets the `userId`, on-device paywall assignments, and data stored
     * by Superwall.
     */
    fun reset() {
        reset(duringIdentify = false)
    }

    /// Asynchronously resets. Presentation of paywalls is suspended until reset completes.
    internal fun reset(duringIdentify: Boolean) {
        dependencyContainer.identityManager.reset(duringIdentify)
        dependencyContainer.storage.reset()
        dependencyContainer.paywallManager.resetCache()
        presentationItems.reset()
        dependencyContainer.configManager.reset()
    }

//
//    fun present() {
//        // Present the Superwall
//        Log.println(Log.INFO, "Superwall", "Superwall present")
//
//        // Find the first paywall in the config
//        val paywall = config?.paywalls?.firstOrNull()
//        if (paywall != null) {
//            // Show the paywall
//            Log.println(Log.INFO, "Superwall", "Superwall show paywall")
//
//
//        }
//
//    }

    //region Deep Links
    /// Handles a deep link sent to your app to open a preview of your paywall.
    ///
    /// You can preview your paywall on-device before going live by utilizing paywall previews. This uses a deep link to render a
    /// preview of a paywall you've configured on the Superwall dashboard on your device. See
    /// [In-App Previews](https://docs.superwall.com/docs/in-app-paywall-previews) for
    /// more.
    ///
    /// - Parameters:
    ///   - uri: The URL of the deep link.
    /// - Returns: A `Bool` that is `true` if the deep link was handled.
    fun handleDeepLink(uri: Uri): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            track(InternalSuperwallEvent.DeepLink(uri = uri))
        }

        // TODO: https://linear.app/superwall/issue/SW-2340/[android]-add-debug-manager
//        return dependencyContainer.debugManager.handleDeepLink(url = url)
        return false
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
                    dependencyContainer.transactionManager.purchase(
                        paywallEvent.productId,
                        paywallViewController
                    )
                }
                is InitiateRestore -> {
                    dependencyContainer.storeKitManager.purchaseController.tryToRestore(paywallViewController)
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

