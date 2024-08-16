package com.superwall.sdk.config

import android.content.Context
import android.webkit.WebView
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.dependencies.DeviceInfoFactory
import com.superwall.sdk.dependencies.RequestFactory
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Result
import com.superwall.sdk.misc.awaitFirstValidConfig
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.paywall.CacheKey
import com.superwall.sdk.models.paywall.PaywallIdentifier
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.JavascriptEvaluator
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// TODO: Re-enable those params
open class ConfigManager(
    private val context: Context,
    private val storeKitManager: StoreKitManager,
    private val storage: Storage,
    private val network: Network,
    private val deviceHelper: DeviceHelper,
    var options: SuperwallOptions,
    private val paywallManager: PaywallManager,
    private val factory: Factory,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    interface Factory :
        RequestFactory,
        DeviceInfoFactory,
        RuleAttributesFactory,
        DeviceHelperFactory,
        JavascriptEvaluator.Factory

    // The configuration of the Superwall dashboard
    val configState = MutableStateFlow<Result<ConfigState>>(Result.Success(ConfigState.Retrieving))

    // Convenience variable to access config
    val config: Config?
        get() = configState.value.getSuccess()?.getConfig()

    // A flow that emits just once only when `config` is non-`nil`.
    val hasConfig: Flow<Config> =
        configState
            .mapNotNull { it.getSuccess()?.getConfig() }
            .take(1)

    // A dictionary of triggers by their event name.
    private var _triggersByEventName = mutableMapOf<String, Trigger>()
    var triggersByEventName: Map<String, Trigger>
        get() = _triggersByEventName
        set(value) {
            _triggersByEventName = value.toMutableMap()
        }

    // A memory store of assignments that are yet to be confirmed.
    private var _unconfirmedAssignments = mutableMapOf<ExperimentID, Experiment.Variant>()
    private var currentPreloadingTask: Job? = null

    var unconfirmedAssignments: Map<ExperimentID, Experiment.Variant>
        get() = _unconfirmedAssignments
        set(value) {
            _unconfirmedAssignments = value.toMutableMap()
        }

    suspend fun fetchConfiguration() {
        try {
            val configDeferred =
                ioScope.async {
                    network.getConfig {
                        // Emit retrying state
                        configState.update { Result.Success(ConfigState.Retrying) }
                    }
                }

            val geoDeferred =
                ioScope.async {
                    deviceHelper.getGeoInfo()
                }
            val attributesDeferred = ioScope.async { factory.makeSessionDeviceAttributes() }

            // Await results from both operations
            val (result, _, attributes) =
                listOf(
                    configDeferred,
                    geoDeferred,
                    attributesDeferred,
                ).awaitAll()
            ioScope.launch {
                @Suppress("UNCHECKED_CAST")
                Superwall.instance.track(InternalSuperwallEvent.DeviceAttributes(attributes as HashMap<String, Any>))
            }
            val config = result as Config
            ioScope.launch { sendProductsBack(config) }

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.superwallCore,
                message = "Fetched Configuration: $config",
            )

            processConfig(config)

            // Preload all products
            if (options.paywalls.shouldPreload) {
                val productIds = config.paywalls.flatMap { it.productIds }.toSet()
                try {
                    storeKitManager.products(productIds)
                } catch (e: Throwable) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.productsManager,
                        message = "Failed to preload products",
                        error = e,
                    )
                }
            }

            configState.emit(Result.Success(ConfigState.Retrieved(config)))

            // TODO: Re-enable those params
//                storeKitManager.loadPurchasedProducts()
            ioScope.launch { preloadPaywalls() }
        } catch (e: Throwable) {
            configState.emit(Result.Failure(e))
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.superwallCore,
                message = "Failed to Fetch Configuration",
                error = e,
            )
        }
    }

    fun reset() {
        val config = configState.value.getSuccess()?.getConfig() ?: return

        unconfirmedAssignments = mutableMapOf()
        choosePaywallVariants(config.triggers)
        ioScope.launch { preloadPaywalls() }
    }

    private fun choosePaywallVariants(triggers: Set<Trigger>) {
        updateAssignments { confirmedAssignments ->
            ConfigLogic.chooseAssignments(
                fromTriggers = triggers,
                confirmedAssignments = confirmedAssignments,
            )
        }
    }

    suspend fun getAssignments() {
        val config = configState.awaitFirstValidConfig() ?: return

        config.triggers.takeUnless { it.isEmpty() }?.let { triggers ->
            try {
                val assignments = network.getAssignments()

                updateAssignments { confirmedAssignments ->
                    ConfigLogic.transferAssignmentsFromServerToDisk(
                        assignments = assignments,
                        triggers = triggers,
                        confirmedAssignments = confirmedAssignments,
                        unconfirmedAssignments = unconfirmedAssignments,
                    )
                }

                if (options.paywalls.shouldPreload) {
                    ioScope.launch { preloadAllPaywalls() }
                }
            } catch (e: Throwable) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.configManager,
                    message = "Error retrieving assignments.",
                    error = e,
                )
            }
        }
    }

    private fun processConfig(config: Config) {
        storage.save(config.featureFlags.disableVerboseEvents, DisableVerboseEvents)
        triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)
        choosePaywallVariants(config.triggers)
    }

    fun confirmAssignment(assignment: ConfirmableAssignment) {
        val postback: AssignmentPostback = AssignmentPostback.create(assignment)
        ioScope.launch(Dispatchers.IO) { network.confirmAssignments(postback) }

        updateAssignments { confirmedAssignments ->
            ConfigLogic.move(
                assignment,
                unconfirmedAssignments,
                confirmedAssignments,
            )
        }
    }

    private fun updateAssignments(operation: (Map<ExperimentID, Experiment.Variant>) -> ConfigLogic.AssignmentOutcome) {
        var confirmedAssignments = storage.getConfirmedAssignments()

        val updatedAssignments = operation(confirmedAssignments)
        unconfirmedAssignments = updatedAssignments.unconfirmed.toMutableMap()
        confirmedAssignments = updatedAssignments.confirmed.toMutableMap()

        storage.saveConfirmedAssignments(confirmedAssignments)
    }

    // Preloading Paywalls
    private fun getTreatmentPaywallIds(triggers: Set<Trigger>): Set<String> {
        val config = configState.value.getSuccess()?.getConfig() ?: return emptySet()
        val preloadableTriggers = ConfigLogic.filterTriggers(triggers, config.preloadingDisabled)
        if (preloadableTriggers.isEmpty()) return emptySet()
        val confirmedAssignments = storage.getConfirmedAssignments()
        return ConfigLogic.getActiveTreatmentPaywallIds(
            preloadableTriggers,
            confirmedAssignments,
            unconfirmedAssignments,
        )
    }

    // Preloads paywalls.
    private suspend fun preloadPaywalls() {
        if (!options.paywalls.shouldPreload) return
        preloadAllPaywalls()
    }

    // Preloads paywalls referenced by triggers.
    suspend fun preloadAllPaywalls() {
        if (currentPreloadingTask != null) {
            return
        }

        currentPreloadingTask =
            ioScope.launch {
                val config = configState.awaitFirstValidConfig() ?: return@launch
                val js = factory.provideJavascriptEvaluator(context)
                val expressionEvaluator =
                    ExpressionEvaluator(
                        evaluator = js,
                        storage = storage,
                        factory = factory,
                    )
                val triggers =
                    ConfigLogic.filterTriggers(
                        config.triggers,
                        preloadingDisabled = config.preloadingDisabled,
                    )
                val confirmedAssignments = storage.getConfirmedAssignments()
                val paywallIds =
                    ConfigLogic.getAllActiveTreatmentPaywallIds(
                        triggers = triggers,
                        confirmedAssignments = confirmedAssignments,
                        unconfirmedAssignments = unconfirmedAssignments,
                        expressionEvaluator = expressionEvaluator,
                    )
                preloadPaywalls(paywallIdentifiers = paywallIds)

                currentPreloadingTask = null
            }
    }

    // Preloads paywalls referenced by the provided triggers.
    suspend fun preloadPaywallsByNames(eventNames: Set<String>) {
        val config = configState.awaitFirstValidConfig() ?: return
        val triggersToPreload = config.triggers.filter { eventNames.contains(it.eventName) }
        val triggerPaywallIdentifiers = getTreatmentPaywallIds(triggersToPreload.toSet())
        preloadPaywalls(triggerPaywallIdentifiers)
    }

    // Preloads paywalls referenced by triggers.
    private suspend fun preloadPaywalls(paywallIdentifiers: Set<String>) {
        val webviewExists =
            WebView.getCurrentWebViewPackage() != null

        if (webviewExists) {
            ioScope.launch {
                // List to hold all the Deferred objects
                val tasks = mutableListOf<Deferred<Any>>()

                for (identifier in paywallIdentifiers) {
                    val task =
                        async {
                            // Your asynchronous operation
                            val request =
                                factory.makePaywallRequest(
                                    eventData = null,
                                    responseIdentifiers =
                                        ResponseIdentifiers(
                                            paywallId = identifier,
                                            experiment = null,
                                        ),
                                    overrides = null,
                                    isDebuggerLaunched = false,
                                    presentationSourceType = null,
                                    retryCount = 6,
                                )
                            try {
                                paywallManager.getPaywallView(
                                    request = request,
                                    isForPresentation = true,
                                    isPreloading = true,
                                    delegate = null,
                                )
                            } catch (e: Exception) {
                                // Handle exception
                            }
                        }
                    tasks.add(task)
                }
                // Await all tasks
                tasks.awaitAll()
            }
        }
    }

    internal suspend fun refreshConfiguration() {
        // Make sure config already exists
        val oldConfig = config ?: return

        // Ensure the config refresh feature flag is enabled
        if (!oldConfig.featureFlags.enableConfigRefresh) {
            return
        }

        try {
            val newConfig =
                network.getConfig {}
            paywallManager.resetPaywallRequestCache()
            removeUnusedPaywallVCsFromCache(oldConfig, newConfig)
            processConfig(newConfig)
            configState.update { Result.Success(ConfigState.Retrieved(newConfig)) }
            Superwall.instance.track(InternalSuperwallEvent.ConfigRefresh)
            ioScope.launch { preloadPaywalls() }
        } catch (e: Exception) {
            Logger.debug(
                logLevel = LogLevel.warn,
                scope = LogScope.superwallCore,
                message = "Failed to refresh configuration.",
                info = null,
                error = e,
            )
        }
    }

    private suspend fun removeUnusedPaywallVCsFromCache(
        oldConfig: Config,
        newConfig: Config,
    ) {
        val oldPaywalls = oldConfig.paywalls
        val newPaywalls = newConfig.paywalls

        val presentedPaywallId = paywallManager.currentView?.paywall?.identifier
        val oldPaywallCacheIds: Map<PaywallIdentifier, CacheKey> =
            oldPaywalls
                .map { it.identifier to it.cacheKey }
                .toMap()
        val newPaywallCacheIds: Map<PaywallIdentifier, CacheKey> = newPaywalls.map { it.identifier to it.cacheKey }.toMap()

        val removedIds: Set<PaywallIdentifier> =
            (oldPaywallCacheIds.keys - newPaywallCacheIds.keys).toSet()

        val changedIds =
            removedIds +
                newPaywalls
                    .filter {
                        val oldCacheKey = oldPaywallCacheIds[it.identifier]
                        val keyChanged = oldCacheKey != newPaywallCacheIds[it.identifier]
                        oldCacheKey != null && keyChanged
                    }.map { it.identifier } - presentedPaywallId

        changedIds.toSet().filterNotNull().forEach {
            paywallManager.removePaywallView(it)
        }
    }

// Assuming other necessary classes and objects are defined elsewhere

    // This sends product data back to the dashboard.
    private suspend fun sendProductsBack(config: Config) {
//        if (!config.featureFlags.enablePostback) return@coroutineScope
//        val milliseconds = 1000L
//        val nanoseconds = milliseconds * 1_000_000L
//        val duration = config.postback.postbackDelay * nanoseconds
//
//        delay(duration)
//        try {
//            val productIds = config.postback.productsToPostBack.map { it.identifier }
//            val products = storeKitManager.getProducts(productIds)
//            val postbackProducts = products.productsById.values.map(::PostbackProduct)
//            val postback = Postback(postbackProducts)
//            network.sendPostback(postback)
//        } catch (e: Exception) {
//            Logger.debug(LogLevel.ERROR, DebugViewController, "No Paywall Response", null, e)
//        }
    }
}
