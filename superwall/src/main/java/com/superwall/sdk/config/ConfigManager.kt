package com.superwall.sdk.config

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.Network
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// TODO: Re-enable those params
open class ConfigManager(
//    private val storeKitManager: StoreKitManager,
    private val storage: Storage,
    private val network: Network,
    options: SuperwallOptions? = null,
//    private val paywallManager: PaywallManager,
//    private val factory: RequestFactory & DeviceInfoFactory
) {
    var options = SuperwallOptions()

    protected val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> get() = _config

    // A flow that emits just once only when `config` is non-`nil`.
    val hasConfig: Flow<Config> = config.filterNotNull()

    // A dictionary of triggers by their event name.
    private var _triggersByEventName = mutableMapOf<String, Trigger>()
    var triggersByEventName: Map<String, Trigger>
        get() = _triggersByEventName
        set(value) {
            _triggersByEventName = value.toMutableMap()
        }

    // A memory store of assignments that are yet to be confirmed.
    private var _unconfirmedAssignments = mutableMapOf<ExperimentID, Experiment.Variant>()
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
        coroutineScope {
            try {
                val config = network.getConfig()
                launch { sendProductsBack(config) }

                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.superwallCore,
                    message = "Fetched Configuration: $config",
                )

                triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)
                choosePaywallVariants(config.triggers)
                this@ConfigManager._config.value = config

                // TODO: Re-enable those params
//                storeKitManager.loadPurchasedProducts()
                launch { preloadPaywalls() }
            } catch (e: Exception) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.superwallCore,
                    message = "Failed to Fetch Configuration",
                    info = null,
                    error = e
                )
            }
        }
    }

    suspend fun reset() {
        val _config = config.value
        if (_config == null) {
            return
        }

        unconfirmedAssignments = mutableMapOf()
        choosePaywallVariants(_config.triggers)
        GlobalScope.launch(Dispatchers.IO) { preloadPaywalls() }
    }

    suspend private fun choosePaywallVariants(triggers: Set<Trigger>) {
        updateAssignments { confirmedAssignments ->
            ConfigLogic.chooseAssignments(
                fromTriggers = triggers,
                confirmedAssignments = confirmedAssignments
            )
        }
    }

    suspend fun getAssignments() {
        // Wait for a config to be available
        val config = config.first { it != null }

        config!!.triggers.takeUnless { it.isEmpty() }?.let { triggers ->
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

//                if (Superwall.shared.options.paywalls.shouldPreload) {
                GlobalScope.launch(Dispatchers.IO) { preloadAllPaywalls() }
//                }
            } catch (e: Exception) {
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
    suspend private fun getTreatmentPaywallIds(triggers: Set<Trigger>): Set<String> {
        val config = config.filterNotNull().first() ?: return emptySet()
        val preloadableTriggers = ConfigLogic.filterTriggers(triggers, config.preloadingDisabled)
        if (preloadableTriggers.isEmpty()) return emptySet()
        val confirmedAssignments = storage.getConfirmedAssignments()
        return ConfigLogic.getActiveTreatmentPaywallIds(
            preloadableTriggers,
            confirmedAssignments,
            unconfirmedAssignments
        )
    }


    // Preloads paywalls.
    private suspend fun preloadPaywalls() = coroutineScope {
//        if (!Superwall.shared.options.paywalls.shouldPreload) return@coroutineScope
        preloadAllPaywalls()
    }

    // Preloads paywalls referenced by triggers.
    private suspend fun preloadAllPaywalls() = coroutineScope {
        val config = config.filterNotNull().first()
        val triggers = ConfigLogic.filterTriggers(config.triggers, config.preloadingDisabled)
        val confirmedAssignments = storage.getConfirmedAssignments()
        val paywallIds = ConfigLogic.getAllActiveTreatmentPaywallIds(
            triggers,
            confirmedAssignments,
            unconfirmedAssignments
        )
        preloadPaywalls(paywallIds)
    }

    // Preloads paywalls referenced by the provided triggers.
    suspend fun preloadPaywallsByNames(eventNames: Set<String>) = coroutineScope {
        val config = config.filterNotNull().first()
        val triggersToPreload = config.triggers.filter { eventNames.contains(it.eventName) }
        val triggerPaywallIdentifiers = getTreatmentPaywallIds(triggersToPreload.toSet())
        preloadPaywalls(triggerPaywallIdentifiers)
    }

    // Preloads paywalls referenced by triggers.
    private fun preloadPaywalls(paywallIdentifiers: Set<String>) {
        // TODO: Re-enable this
//        paywallIdentifiers.forEach { identifier ->
//            launch {
//                val request = factory.makePaywallRequest(null, ResponseIdentifiers(paywallId = identifier), null, false)
//                val _ = try {
//                    paywallManager.getPaywallViewController(request, true, null)
//                } catch (e: Exception) {
//                    null
//                }
//            }
//        }
    }

    // This sends product data back to the dashboard.
    private suspend fun sendProductsBack(config: Config) = coroutineScope {
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