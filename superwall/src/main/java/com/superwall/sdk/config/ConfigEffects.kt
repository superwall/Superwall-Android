package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.engine.SdkEvent
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.misc.into
import com.superwall.sdk.misc.onError
import com.superwall.sdk.misc.primitives.Effect
import com.superwall.sdk.misc.then
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestModeManager
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal interface ConfigEffectDeps {
    val context: Context
    val network: SuperwallAPI
    val storage: Storage
    val localStorage: LocalStorage
    val storeManager: StoreManager
    val entitlements: Entitlements
    val deviceHelper: DeviceHelper
    val paywallManager: PaywallManager
    val paywallPreload: PaywallPreload
    val webPaywallRedeemer: (() -> WebPaywallRedeemer)?
    val testModeManager: TestModeManager?
    val options: () -> SuperwallOptions
    val configProvider: () -> Config?
    val unconfirmedAssignmentsProvider: () -> Map<ExperimentID, Experiment.Variant>
    val awaitUntilNetwork: suspend () -> Unit
    val track: suspend (InternalSuperwallEvent) -> Unit
    val evaluateTestMode: (Config) -> Unit
    val subscriptionStatus: () -> SubscriptionStatus
}

internal sealed class ConfigEffect(
    val execute: suspend ConfigEffectDeps.(dispatch: (SdkEvent) -> Unit) -> Unit,
) : Effect {

    /**
     * The main fetch: network call + enrichment + attributes in parallel,
     * then dispatches ConfigRetrieved/ConfigFailed + AssignmentsUpdated.
     */
    object FetchConfig : ConfigEffect({ dispatch ->
        val oldConfig = storage.read(LatestConfig)
        val status = subscriptionStatus()
        val cacheLimit = if (status is SubscriptionStatus.Active) 500.milliseconds else 1.seconds
        var isConfigFromCache = false
        var isEnrichmentFromCache = false

        val configRetryCount = AtomicInteger(0)
        var configDuration = 0L

        coroutineScope {
            val configDeferred = async {
                val start = System.currentTimeMillis()
                val result = if (oldConfig?.featureFlags?.enableConfigRefresh == true) {
                    try {
                        withTimeout(cacheLimit) {
                            network.getConfig {
                                dispatch(
                                    SdkState.Updates.UpdateConfig(ConfigSlice.Updates.Retrying),
                                )
                                configRetryCount.incrementAndGet()
                                awaitUntilNetwork()
                            }.into {
                                if (it is Either.Failure) {
                                    isConfigFromCache = true
                                    Either.Success(oldConfig)
                                } else {
                                    it
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        oldConfig?.let {
                            isConfigFromCache = true
                            Either.Success(it)
                        } ?: Either.Failure(e)
                    }
                } else {
                    network.getConfig {
                        dispatch(
                            SdkState.Updates.UpdateConfig(ConfigSlice.Updates.Retrying),
                        )
                        configRetryCount.incrementAndGet()
                        awaitUntilNetwork()
                    }
                }
                configDuration = System.currentTimeMillis() - start
                result
            }

            val enrichmentDeferred = async {
                val cached = storage.read(LatestEnrichment)
                if (oldConfig?.featureFlags?.enableConfigRefresh == true) {
                    val res = deviceHelper.getEnrichment(0, cacheLimit)
                        .then { storage.write(LatestEnrichment, it) }
                    if (res.getSuccess() == null) {
                        cached?.let {
                            deviceHelper.setEnrichment(cached)
                            isEnrichmentFromCache = true
                            Either.Success(it)
                        } ?: res
                    } else {
                        res
                    }
                } else {
                    deviceHelper.getEnrichment(0, 1.seconds)
                }
            }

            val (configResult, enrichmentResult) = listOf(configDeferred, enrichmentDeferred).awaitAll()

            @Suppress("UNCHECKED_CAST")
            val typedConfigResult = configResult as Either<Config, Throwable>
            @Suppress("UNCHECKED_CAST")
            val typedEnrichmentResult = enrichmentResult as Either<Enrichment, Throwable>

            when (typedConfigResult) {
                is Either.Success -> {
                    val config = typedConfigResult.value
                    // Choose assignments (reads confirmed from storage, pure computation)
                    val confirmed = localStorage.getConfirmedAssignments()
                    val assignmentOutcome = ConfigLogic.chooseAssignments(
                        fromTriggers = config.triggers,
                        confirmedAssignments = confirmed,
                    )

                    dispatch(
                        SdkState.Updates.UpdateConfig(
                            ConfigSlice.Updates.ConfigRetrieved(
                                config = config,
                                isCached = isConfigFromCache,
                                fetchDuration = configDuration,
                                retryCount = configRetryCount.get(),
                                isEnrichmentCached = isEnrichmentFromCache,
                                enrichmentFailed = typedEnrichmentResult.getThrowable() != null,
                            ),
                        ),
                    )

                    dispatch(
                        SdkState.Updates.UpdateConfig(
                            ConfigSlice.Updates.AssignmentsUpdated(
                                unconfirmed = assignmentOutcome.unconfirmed,
                                confirmed = assignmentOutcome.confirmed,
                            ),
                        ),
                    )
                }

                is Either.Failure -> {
                    dispatch(
                        SdkState.Updates.UpdateConfig(
                            ConfigSlice.Updates.ConfigFailed(
                                error = typedConfigResult.error,
                                wasConfigCached = isConfigFromCache,
                            ),
                        ),
                    )
                }
            }
        }
    })

    /** Post-config-retrieval impure side effects. */
    data class ProcessConfigSideEffects(
        val config: Config,
        val isCached: Boolean,
        val isEnrichmentCached: Boolean,
        val enrichmentFailed: Boolean,
    ) : ConfigEffect({ dispatch ->
        // Extract and set entitlements
        ConfigLogic.extractEntitlementsByProductId(config.products).let {
            entitlements.addEntitlementsByProductId(it)
        }
        config.productsV3?.let { v3 ->
            ConfigLogic.extractEntitlementsByProductIdFromCrossplatform(v3).let {
                entitlements.addEntitlementsByProductId(it)
            }
        }

        // Test mode evaluation
        evaluateTestMode(config)

        // Web entitlements check
        if (testModeManager?.isTestMode != true) {
            webPaywallRedeemer?.invoke()?.redeem(WebPaywallRedeemer.RedeemType.Existing)
        }

        // Product preloading
        if (testModeManager?.isTestMode != true && options().paywalls.shouldPreload) {
            val productIds = config.paywalls.flatMap { it.productIds }.toSet()
            try {
                storeManager.products(productIds)
            } catch (e: Throwable) {
                Logger.debug(LogLevel.error, LogScope.productsManager, "Failed to preload products", error = e)
            }
        }

        // Background refresh if config was from cache
        if (isCached) {
            coroutineScope {
                launch { configProvider()?.let { if (it.featureFlags.enableConfigRefresh) refreshConfig(dispatch) } }
            }
        }
        // Enrichment refresh if cached or failed
        if (isEnrichmentCached || enrichmentFailed) {
            deviceHelper.getEnrichment(6, 1.seconds)
        }

        // Preload paywalls
        if (options().paywalls.shouldPreload) {
            paywallPreload.preloadAllPaywalls(config, context)
        }
    })

    /** Background config refresh. */
    data class RefreshConfiguration(val force: Boolean) : ConfigEffect({ dispatch ->
        refreshConfig(dispatch, force)
    })

    /** Preload paywalls. */
    object PreloadPaywalls : ConfigEffect({ dispatch ->
        if (options().paywalls.shouldPreload) {
            configProvider()?.let {
                paywallPreload.preloadAllPaywalls(it, context)
            }
        }
    })

    /** Preload specific paywalls by event names. */
    data class PreloadPaywallsByNames(
        val eventNames: Set<String>,
    ) : ConfigEffect({ dispatch ->
        configProvider()?.let {
            paywallPreload.preloadPaywallsByNames(it, eventNames)
        }
    })

    /** Fetch assignments from server. */
    object FetchAssignments : ConfigEffect({ dispatch ->
        val config = configProvider()
        val triggers = config?.triggers
        if (config != null && triggers != null && triggers.isNotEmpty()) {
            val confirmed = localStorage.getConfirmedAssignments()
            val currentUnconfirmed = unconfirmedAssignmentsProvider()
            network.getAssignments()
                .then { assignments ->
                    val outcome = ConfigLogic.transferAssignmentsFromServerToDisk(
                        assignments = assignments,
                        triggers = triggers,
                        confirmedAssignments = confirmed,
                        unconfirmedAssignments = currentUnconfirmed,
                    )
                    dispatch(
                        SdkState.Updates.UpdateConfig(
                            ConfigSlice.Updates.AssignmentsUpdated(
                                unconfirmed = outcome.unconfirmed,
                                confirmed = outcome.confirmed,
                            ),
                        ),
                    )
                }.onError {
                    Logger.debug(LogLevel.error, LogScope.configManager, "Error retrieving assignments.", error = it)
                }
        }
    })

    /** Post assignment confirmation to server. */
    data class PostAssignmentConfirmation(
        val assignment: ConfirmableAssignment,
    ) : ConfigEffect({ dispatch ->
        val postback = AssignmentPostback.create(assignment)
        network.confirmAssignments(postback)
    })

    /** Save confirmed assignments to local storage. */
    data class SaveConfirmedAssignments(
        val confirmed: Map<ExperimentID, Experiment.Variant>,
    ) : ConfigEffect({ dispatch ->
        localStorage.saveConfirmedAssignments(confirmed)
    })

    /** Side effects for a background-refreshed config. */
    data class HandleConfigRefreshSideEffects(
        val config: Config,
        val oldConfig: Config?,
    ) : ConfigEffect({ dispatch ->
        ConfigLogic.extractEntitlementsByProductId(config.products).let {
            entitlements.addEntitlementsByProductId(it)
        }
        config.productsV3?.let { v3 ->
            ConfigLogic.extractEntitlementsByProductIdFromCrossplatform(v3).let {
                entitlements.addEntitlementsByProductId(it)
            }
        }

        evaluateTestMode(config)

        if (testModeManager?.isTestMode != true) {
            storeManager.loadPurchasedProducts(entitlements.entitlementsByProductId)
        }

        if (options().paywalls.shouldPreload) {
            paywallPreload.preloadAllPaywalls(config, context)
        }
    })
}

/** Helper: performs a background config refresh and dispatches the result. */
private suspend fun ConfigEffectDeps.refreshConfig(
    dispatch: (SdkEvent) -> Unit,
    force: Boolean = false,
) {
    val currentConfig = configProvider() ?: return
    if (!force && !currentConfig.featureFlags.enableConfigRefresh) return

    deviceHelper.getEnrichment(0, 1.seconds)

    val retryCount = AtomicInteger(0)
    val start = System.currentTimeMillis()
    network.getConfig {
        retryCount.incrementAndGet()
        awaitUntilNetwork()
    }.then { newConfig ->
        paywallManager.resetPaywallRequestCache()
        paywallPreload.removeUnusedPaywallVCsFromCache(currentConfig, newConfig)

        val confirmed = localStorage.getConfirmedAssignments()
        val assignmentOutcome = ConfigLogic.chooseAssignments(
            fromTriggers = newConfig.triggers,
            confirmedAssignments = confirmed,
        )

        dispatch(
            SdkState.Updates.UpdateConfig(
                ConfigSlice.Updates.ConfigRefreshed(
                    config = newConfig,
                    oldConfig = currentConfig,
                    fetchDuration = System.currentTimeMillis() - start,
                    retryCount = retryCount.get(),
                ),
            ),
        )
        dispatch(
            SdkState.Updates.UpdateConfig(
                ConfigSlice.Updates.AssignmentsUpdated(
                    unconfirmed = assignmentOutcome.unconfirmed,
                    confirmed = assignmentOutcome.confirmed,
                ),
            ),
        )
    }.onError {
        Logger.debug(LogLevel.warn, LogScope.superwallCore, "Failed to refresh configuration.", error = it)
    }
}
