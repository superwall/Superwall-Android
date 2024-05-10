package com.superwall.sdk.config

import android.content.Context
import android.webkit.WebView
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.config.options.SuperwallOptions
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
import com.superwall.sdk.models.config.WebArchiveManifest
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluator
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.webarchive.archive.WebArchiveLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

// TODO: Re-enable those params
open class ConfigManager(
    private val context: Context,
    private val storeKitManager: StoreKitManager,
    private val storage: Storage,
    private val network: Network,
    options: SuperwallOptions? = null,
    private val paywallManager: PaywallManager,
    private val webArchiveLibrary: WebArchiveLibrary,
    private val factory: Factory
) {
    interface Factory : RequestFactory, DeviceInfoFactory, RuleAttributesFactory {}

    var options = SuperwallOptions()

    // The configuration of the Superwall dashboard
    val configState = MutableStateFlow<Result<ConfigState>>(Result.Success(ConfigState.Retrieving))

    // Convenience variable to access config
    val config: Config?
        get() = configState.value.getSuccess()?.getConfig()

    // A flow that emits just once only when `config` is non-`nil`.
    val hasConfig: Flow<Config> = configState
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

    // Initializer
    init {
        if (options != null) {
            this.options = options
        }
    }

    // Remaining methods should be converted similarly, using Kotlin's coroutines for async tasks
    // and other relevant Kotlin features. Here's an example of one method:
    suspend fun fetchConfiguration() {
        try {
            val config = network.getConfig() {
                CoroutineScope(Dispatchers.IO).launch {
                    configState.emit(Result.Success(ConfigState.Retrying))
                }
            }
            CoroutineScope(Dispatchers.IO).launch { sendProductsBack(config) }

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.superwallCore,
                message = "Fetched Configuration: $config",
            )

            storage.save(config.featureFlags.disableVerboseEvents, DisableVerboseEvents)
            triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)
            choosePaywallVariants(config.triggers)

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
                        error = e
                    )
                }
            }

            configState.emit(Result.Success(ConfigState.Retrieved(config)))

            // TODO: Re-enable those params
//                storeKitManager.loadPurchasedProducts()
            with(CoroutineScope(Dispatchers.IO)) {
                launch {
                    cachePaywallsFromManifest(config.paywalls)
                }
                launch {
                    //Preload paywalls that do not need to be archived
                    preloadPaywalls(excludeIds = config.paywalls.filter {
                        it.manifest != null && it.manifest.use != WebArchiveManifest.Usage.NEVER
                    }.map { it.identifier })
                }
            }
        } catch (e: Throwable) {
            configState.emit(Result.Failure(e))
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.superwallCore,
                message = "Failed to Fetch Configuration",
                error = e
            )
        }
    }

    fun reset() {
        val config = configState.value.getSuccess()?.getConfig() ?: return

        unconfirmedAssignments = mutableMapOf()
        choosePaywallVariants(config.triggers)
        CoroutineScope(Dispatchers.IO).launch { preloadPaywalls() }
    }

    private fun choosePaywallVariants(triggers: Set<Trigger>) {
        updateAssignments { confirmedAssignments ->
            ConfigLogic.chooseAssignments(
                fromTriggers = triggers,
                confirmedAssignments = confirmedAssignments
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
                        unconfirmedAssignments = unconfirmedAssignments
                    )
                }

                if (options.paywalls.shouldPreload) {
                    CoroutineScope(Dispatchers.IO).launch { preloadAllPaywalls() }
                }
            } catch (e: Throwable) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.configManager,
                    message = "Error retrieving assignments.",
                    error = e
                )
            }
        }
    }

    fun confirmAssignment(assignment: ConfirmableAssignment) {
        val postback: AssignmentPostback = AssignmentPostback.create(assignment)
        GlobalScope.launch(Dispatchers.IO) { network.confirmAssignments(postback) }

        updateAssignments { confirmedAssignments ->
            ConfigLogic.move(
                assignment,
                unconfirmedAssignments,
                confirmedAssignments
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
            unconfirmedAssignments
        )
    }


    private suspend fun cachePaywallsFromManifest(paywalls: List<Paywall>) {
        paywalls
            .filter {
                it.manifest != null
            }
            .forEach {
                webArchiveLibrary.downloadManifest(it.identifier, it.url.toString(), it.manifest!!)
            }
    }

    // Preloads paywalls.
    private suspend fun preloadPaywalls(excludeIds: List<String> = emptyList()) {
        if (!options.paywalls.shouldPreload) return
        preloadAllPaywalls(excludeIds)
    }

    // Preloads paywalls referenced by triggers.
    suspend fun preloadAllPaywalls(excludeIds: List<String> = emptyList()) {
        if (currentPreloadingTask != null) {
            return
        }

        currentPreloadingTask = CoroutineScope(Dispatchers.IO).launch {
            val config = configState.awaitFirstValidConfig() ?: return@launch

            val expressionEvaluator = ExpressionEvaluator(
                context = context,
                storage = storage,
                factory = factory
            )
            val triggers = ConfigLogic.filterTriggers(
                config.triggers,
                preloadingDisabled = config.preloadingDisabled
            )
            val confirmedAssignments = storage.getConfirmedAssignments()
            val paywallIds = ConfigLogic.getAllActiveTreatmentPaywallIds(
                triggers = triggers,
                confirmedAssignments = confirmedAssignments,
                unconfirmedAssignments = unconfirmedAssignments,
                expressionEvaluator = expressionEvaluator
            )
            preloadPaywallsWithIds(paywallIdentifiers = paywallIds.filterNot { excludeIds.contains(it) }
                .toSet())

            currentPreloadingTask = null
        }
    }

    // Preloads paywalls referenced by the provided triggers.
    suspend fun preloadPaywallsByNames(eventNames: Set<String>) {
        val config = configState.awaitFirstValidConfig() ?: return
        val triggersToPreload = config.triggers.filter { eventNames.contains(it.eventName) }
        val triggerPaywallIdentifiers = getTreatmentPaywallIds(triggersToPreload.toSet())
        preloadPaywallsWithIds(triggerPaywallIdentifiers)
    }

    // Preloads paywalls referenced by triggers.
    private suspend fun preloadPaywallsWithIds(paywallIdentifiers: Set<String>) {
        val webviewExists = WebView.getCurrentWebViewPackage() != null
        if (webviewExists) {
            coroutineScope {
                // List to hold all the Deferred objects
                val tasks = mutableListOf<Deferred<Any>>()

                for (identifier in paywallIdentifiers) {
                    val task = async {
                        // Your asynchronous operation
                        val request = factory.makePaywallRequest(
                            eventData = null,
                            responseIdentifiers = ResponseIdentifiers(
                                paywallId = identifier,
                                experiment = null
                            ),
                            overrides = null,
                            isDebuggerLaunched = false,
                            presentationSourceType = null,
                            retryCount = 6
                        )
                        try {
                            paywallManager.getPaywallViewController(
                                request = request,
                                isForPresentation = true,
                                isPreloading = true,
                                delegate = null
                            )
                        } catch (e: Exception) {
                            // Handle exception
                        }
                    }
                    tasks.add(task)
                }

                // Await all tasks
                tasks.forEach { it.await() }
            }
        }
    }

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