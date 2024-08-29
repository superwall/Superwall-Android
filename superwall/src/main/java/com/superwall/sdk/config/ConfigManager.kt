package com.superwall.sdk.config

import Assignments
import android.content.Context
import awaitUntilNetworkExists
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
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.awaitFirstValidConfig
import com.superwall.sdk.misc.fold
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.JavascriptEvaluator
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val assignments: Assignments,
    private val paywallPreload: PaywallPreload,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    interface Factory :
        RequestFactory,
        DeviceInfoFactory,
        RuleAttributesFactory,
        DeviceHelperFactory,
        JavascriptEvaluator.Factory

    // The configuration of the Superwall dashboard
    val configState = MutableStateFlow<ConfigState>(ConfigState.Retrieving)

    // Convenience variable to access config
    val config: Config?
        get() = configState.value.getConfig()

    // A flow that emits just once only when `config` is non-`nil`.
    val hasConfig: Flow<Config> =
        configState
            .mapNotNull { it.getConfig() }
            .take(1)

    // A dictionary of triggers by their event name.
    private var _triggersByEventName = mutableMapOf<String, Trigger>()
    var triggersByEventName: Map<String, Trigger>
        get() = _triggersByEventName
        set(value) {
            _triggersByEventName = value.toMutableMap()
        }

    // A memory store of assignments that are yet to be confirmed.

    val unconfirmedAssignments: Map<ExperimentID, Experiment.Variant>
        get() = assignments.unconfirmedAssignments

    suspend fun fetchConfiguration() {
        fetchConfig()
    }

    private suspend fun fetchConfig() {
        val configDeferred =
            ioScope.async {
                network.getConfig {
                    // Emit retrying state
                    configState.update { ConfigState.Retrying }
                    context.awaitUntilNetworkExists()
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
        val configResult = result as Either<Config>

        configResult.fold({ config ->
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

            configState.update { ConfigState.Retrieved(config) }

            // TODO: Re-enable those params
//                storeKitManager.loadPurchasedProducts()
            ioScope.launch { preloadPaywalls() }
        }, { e ->
            configState.update { ConfigState.Failed(e) }
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.superwallCore,
                message = "Failed to Fetch Configuration",
                error = e,
            )
        })
    }

    fun reset() {
        val config = configState.value.getConfig() ?: return

        assignments.reset()
        assignments.choosePaywallVariants(config.triggers)
        ioScope.launch { preloadPaywalls() }
    }

    suspend fun getAssignments() {
        val config = configState.awaitFirstValidConfig() ?: return

        config.triggers.takeUnless { it.isEmpty() }?.let { triggers ->
            try {
                assignments.getAssignments(triggers)

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
        assignments.choosePaywallVariants(config.triggers)
    }

    // Preloading Paywalls

    // Preloads paywalls.
    private suspend fun preloadPaywalls() {
        if (!options.paywalls.shouldPreload) return
        preloadAllPaywalls()
    }

    // Preloads paywalls referenced by triggers.
    suspend fun preloadAllPaywalls() =
        paywallPreload.preloadAllPaywalls(
            configState.awaitFirstValidConfig(),
            context,
        )

    // Preloads paywalls referenced by the provided triggers.
    suspend fun preloadPaywallsByNames(eventNames: Set<String>) =
        paywallPreload.preloadPaywallsByNames(
            configState.awaitFirstValidConfig(),
            eventNames,
        )

    // Preloads paywalls referenced by triggers.

    internal suspend fun refreshConfiguration() {
        // Make sure config already exists
        val oldConfig = config ?: return

        // Ensure the config refresh feature flag is enabled
        if (!oldConfig.featureFlags.enableConfigRefresh) {
            return
        }

        network
            .getConfig {
                context.awaitUntilNetworkExists()
            }.fold(
                onSuccess = { newConfig ->
                    paywallManager.resetPaywallRequestCache()
                    paywallPreload.removeUnusedPaywallVCsFromCache(oldConfig, newConfig)
                    processConfig(newConfig)
                    configState.update { ConfigState.Retrieved(newConfig) }
                    Superwall.instance.track(InternalSuperwallEvent.ConfigRefresh)
                    ioScope.launch { preloadPaywalls() }
                },
                onFailure = {
                    Logger.debug(
                        logLevel = LogLevel.warn,
                        scope = LogScope.superwallCore,
                        message = "Failed to refresh configuration.",
                        info = null,
                        error = it,
                    )
                },
            )
    }
}
