package com.superwall.sdk.config

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.TestModeModal.*
import com.superwall.sdk.identity.IdentityState
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.into
import com.superwall.sdk.misc.onError
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.misc.then
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.TestStoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallProductPlatform
import com.superwall.sdk.store.testmode.ui.TestModeModal
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class SdkConfigState(
    val phase: Phase = Phase.None,
    val triggersByEventName: Map<String, Trigger> = emptyMap(),
    val unconfirmedAssignments: Map<ExperimentID, Experiment.Variant> = emptyMap(),
) {
    sealed class Phase {
        object None : Phase()

        object Retrieving : Phase()

        object Retrying : Phase()

        data class Retrieved(
            val config: Config,
            val isCachedConfig: Boolean = false,
            val isCachedEnrichment: Boolean = false,
            val fetchDuration: Long = 0,
            val retryCount: Int = 0,
        ) : Phase()

        data class Failed(
            val error: Throwable,
        ) : Phase()
    }

    val config: Config?
        get() = (phase as? Phase.Retrieved)?.config
    val isRetrieved: Boolean
        get() = phase is Phase.Retrieved

    // -----------------------------------------------------------------------
    // Pure state mutations — (SdkConfigState) -> SdkConfigState, nothing else
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (SdkConfigState) -> SdkConfigState,
    ) : Reducer<SdkConfigState> {
        /** Guards against duplicate fetches. Sets phase to Retrieving. */
        object FetchRequested : Updates({ state ->
            if (state.phase is Phase.Retrieving) {
                state
            } else {
                state.copy(phase = Phase.Retrieving)
            }
        })

        /** Network retry happening. */
        object Retrying : Updates({ state ->
            state.copy(phase = Phase.Retrying)
        })

        /**
         * Config fetched successfully — pure state transform only.
         */
        data class ConfigRetrieved(
            val config: Config,
            val isCachedConfig: Boolean = false,
            val isCachedEnrichment: Boolean = false,
            val fetchDuration: Long = 0,
            val retryCount: Int = 0,
        ) : Updates({ state ->
                val triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)
                state.copy(
                    phase =
                        Phase.Retrieved(
                            config = config,
                            isCachedConfig = isCachedConfig,
                            isCachedEnrichment = isCachedEnrichment,
                            fetchDuration = fetchDuration,
                            retryCount = retryCount,
                        ),
                    triggersByEventName = triggersByEventName,
                )
            })

        /** Config fetch failed. */
        data class ConfigFailed(
            val error: Throwable,
        ) : Updates({ state ->
                state.copy(phase = Phase.Failed(error))
            })

        /** Retry fetch when config getter is called in Failed state. */
        object RetryFetch : Updates({ state ->
            if (state.phase is Phase.Failed) {
                state.copy(phase = Phase.Retrieving)
            } else {
                state
            }
        })

        /** Assignments updated after choose or fetch from server. */
        data class AssignmentsUpdated(
            val unconfirmed: Map<ExperimentID, Experiment.Variant>,
        ) : Updates({ state ->
                state.copy(unconfirmedAssignments = unconfirmed)
            })

        /** Confirms a single assignment. */
        data class ConfirmAssignment(
            val assignment: ConfirmableAssignment,
            val confirmedAssignments: Map<ExperimentID, Experiment.Variant>,
        ) : Updates({ state ->
                val outcome =
                    ConfigLogic.move(
                        assignment,
                        state.unconfirmedAssignments,
                        confirmedAssignments,
                    )
                state.copy(unconfirmedAssignments = outcome.unconfirmed)
            })

        /** Reset: clears unconfirmed, re-chooses variants. */
        data class Reset(
            val confirmedAssignments: Map<ExperimentID, Experiment.Variant>,
        ) : Updates({ state ->
                val config = state.config
                if (config != null) {
                    val outcome =
                        ConfigLogic.chooseAssignments(
                            fromTriggers = config.triggers,
                            confirmedAssignments = confirmedAssignments,
                        )
                    state.copy(unconfirmedAssignments = outcome.unconfirmed)
                } else {
                    state
                }
            })

        /** Background config refresh completed successfully. */
        data class ConfigRefreshed(
            val config: Config,
        ) : Updates({ state ->
                val triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)
                state.copy(
                    phase = Phase.Retrieved(config),
                    triggersByEventName = triggersByEventName,
                )
            })
    }

    // -----------------------------------------------------------------------
    // Async work — actions have full access to ConfigContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend ConfigContext.() -> Unit,
    ) : TypedAction<ConfigContext> {
        /**
         * Main fetch logic: network config + enrichment + device attributes in parallel,
         * then process config and update state. Side effects (web entitlements,
         * product preloading, cache recovery) are handled by [HandlePostFetch].
         */
        object FetchConfig : Actions(
            action@{
                update(Updates.FetchRequested)

                val oldConfig = storage.read(LatestConfig)
                val status = entitlements.status.value
                val cacheLimit =
                    if (status is SubscriptionStatus.Active) 500.milliseconds else 1.seconds
                var isConfigFromCache = false
                var isEnrichmentFromCache = false

                val configRetryCount = AtomicInteger(0)
                var configDuration = 0L

                val configDeferred =
                    ioScope.async {
                        val start = System.currentTimeMillis()
                        (
                            if (oldConfig?.featureFlags?.enableConfigRefresh == true) {
                                try {
                                    withTimeout(cacheLimit) {
                                        network
                                            .getConfig {
                                                update(Updates.Retrying)
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
                                    e.printStackTrace()
                                    oldConfig.let {
                                        isConfigFromCache = true
                                        Either.Success(it)
                                    }
                                }
                            } else {
                                network
                                    .getConfig {
                                        update(Updates.Retrying)
                                        configRetryCount.incrementAndGet()
                                        awaitUntilNetwork()
                                    }
                            }
                        ).also {
                            configDuration = System.currentTimeMillis() - start
                        }
                    }

                val enrichmentDeferred =
                    ioScope.async {
                        val cached = storage.read(LatestEnrichment)
                        if (oldConfig?.featureFlags?.enableConfigRefresh == true) {
                            val res =
                                deviceHelper
                                    .getEnrichment(0, cacheLimit)
                                    .then {
                                        storage.write(LatestEnrichment, it)
                                    }
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

                val attributesDeferred = ioScope.async { makeSessionDeviceAttributes() }

                val (result, enriched) =
                    listOf(
                        configDeferred,
                        enrichmentDeferred,
                    ).awaitAll()
                val attributes = attributesDeferred.await()
                ioScope.launch {
                    @Suppress("UNCHECKED_CAST")
                    track(InternalSuperwallEvent.DeviceAttributes(attributes as HashMap<String, Any>))
                }

                @Suppress("UNCHECKED_CAST")
                val configResult = result as Either<Config, Throwable>

                @Suppress("UNCHECKED_CAST")
                val enrichmentResult = enriched as? Either<*, Throwable>

                configResult
                    .then { config ->
                        ProcessConfig(config).execute.invoke(this@action)
                    }.then {
                        actor.update(
                            Updates.ConfigRetrieved(
                                config = it,
                                isCachedConfig = isConfigFromCache,
                                isCachedEnrichment =
                                    isEnrichmentFromCache ||
                                        enrichmentResult?.getThrowable() != null,
                                fetchDuration = configDuration,
                                retryCount = configRetryCount.get(),
                            ),
                        )
                    }.fold(
                        onSuccess = { config ->
                            // Push config to identity so it can configure without awaiting
                            sdkContext.effect(
                                IdentityState.Actions.Configure(
                                    config = config,
                                    neverCalledStaticConfig = neverCalledStaticConfig(),
                                ),
                            )
                            effect(HandlePostFetch)
                        },
                        onFailure = { e ->
                            e.printStackTrace()
                            update(Updates.ConfigFailed(e))
                            if (!isConfigFromCache) {
                                RefreshConfig().execute.invoke(this@action)
                            }
                            track(InternalSuperwallEvent.ConfigFail(e.message ?: "Unknown error"))
                            Logger.debug(
                                logLevel = LogLevel.error,
                                scope = LogScope.superwallCore,
                                message = "Failed to Fetch Configuration",
                                error = e,
                            )
                        },
                    )
            },
        )

        /**
         * Post-fetch side effects after config is successfully retrieved.
         * Reads fetch metadata from [Phase.Retrieved] state to decide:
         * - Track [InternalSuperwallEvent.ConfigRefresh]
         * - Check web entitlements
         * - Preload store products
         * - Trigger cache recovery ([RefreshConfig], enrichment retry)
         * - Preload paywalls
         */
        object HandlePostFetch : Actions(
            action@{
                val phase = actor.state.value.phase as? Phase.Retrieved ?: return@action
                val config = phase.config

                // Track config refresh event
                ioScope.launch {
                    track(
                        InternalSuperwallEvent.ConfigRefresh(
                            isCached = phase.isCachedConfig,
                            buildId = config.buildId,
                            fetchDuration = phase.fetchDuration,
                            retryCount = phase.retryCount,
                        ),
                    )
                }

                // Check web entitlements
                if (testModeManager?.isTestMode != true) {
                    effect(CheckWebEntitlements)
                }

                // Preload store products
                if (testModeManager?.isTestMode != true && options.paywalls.shouldPreload) {
                    val productIds = config.paywalls.flatMap { pw -> pw.productIds }.toSet()
                    try {
                        storeManager.products(productIds)
                    } catch (e: Throwable) {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.productsManager,
                            message = "Failed to preload products",
                            error = e,
                        )
                    }
                }

                // Cache recovery: refresh config if it was served from cache
                if (phase.isCachedConfig) {
                    effect(RefreshConfig())
                }

                // Retry enrichment if it was cached or failed
                if (phase.isCachedEnrichment) {
                    ioScope.launch { deviceHelper.getEnrichment(6, 1.seconds) }
                }

                // Preload paywalls
                effect(PreloadPaywalls)
            },
        )

        /**
         * Background config refresh. Re-fetches from network, processes,
         * and updates state.
         */
        data class RefreshConfig(
            val force: Boolean = false,
        ) : Actions(
                action@{
                    val oldConfig = actor.state.value.config ?: return@action

                    if (!force && !oldConfig.featureFlags.enableConfigRefresh) {
                        return@action
                    }

                    ioScope.launch {
                        deviceHelper.getEnrichment(0, 1.seconds)
                    }

                    val retryCount = AtomicInteger(0)
                    val startTime = System.currentTimeMillis()
                    network
                        .getConfig {
                            retryCount.incrementAndGet()
                            awaitUntilNetwork()
                        }.then {
                            paywallManager.resetPaywallRequestCache()
                            val currentConfig = actor.state.value.config
                            if (currentConfig != null) {
                                paywallPreload.removeUnusedPaywallVCsFromCache(currentConfig, it)
                            }
                        }.then { config ->
                            ProcessConfig(config).execute.invoke(this@action)
                            update(Updates.ConfigRefreshed(config))
                            track(
                                InternalSuperwallEvent.ConfigRefresh(
                                    isCached = false,
                                    buildId = config.buildId,
                                    fetchDuration = System.currentTimeMillis() - startTime,
                                    retryCount = retryCount.get(),
                                ),
                            )
                        }.fold(
                            onSuccess = {
                                effect(PreloadPaywalls)
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
                },
            )

        /** Fetch assignments from the server. */
        object FetchAssignments : Actions(
            action@{
                val config = awaitConfig() ?: return@action

                config.triggers.takeUnless { it.isEmpty() }?.let { triggers ->
                    try {
                        assignments
                            .getAssignments(triggers)
                            .then {
                                effect(PreloadPaywalls)
                            }.onError {
                                Logger.debug(
                                    logLevel = LogLevel.error,
                                    scope = LogScope.configManager,
                                    message = "Error retrieving assignments.",
                                    error = it,
                                )
                            }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.configManager,
                            message = "Error retrieving assignments.",
                            error = e,
                        )
                    }
                }
            },
        )

        /** Reset assignments and re-choose variants. */
        object ResetAssignments : Actions(
            action@{
                val config = actor.state.value.config ?: return@action
                assignments.reset()
                assignments.choosePaywallVariants(config.triggers)
                effect(PreloadPaywalls)
            },
        )

        /** Preload paywalls if enabled. */
        object PreloadPaywalls : Actions(
            action@{
                if (!options.paywalls.shouldPreload) return@action
                val config = awaitConfig() ?: return@action
                paywallPreload.preloadAllPaywalls(config, context)
            },
        )

        /** Preload all paywalls (bypasses shouldPreload check). */
        object PreloadAllPaywalls : Actions(
            action@{
                val config = awaitConfig() ?: return@action
                paywallPreload.preloadAllPaywalls(config, context)
            },
        )

        /** Preload paywalls for specific event names. */
        data class PreloadPaywallsByNames(
            val eventNames: Set<String>,
        ) : Actions(
                action@{
                    val config = awaitConfig() ?: return@action
                    paywallPreload.preloadPaywallsByNames(config, eventNames)
                },
            )

        /**
         * Process a fetched config: persist flags, extract entitlements,
         * choose assignments, evaluate test mode.
         */
        data class ProcessConfig(
            val config: Config,
        ) : Actions({
                storage.write(DisableVerboseEvents, config.featureFlags.disableVerboseEvents)
                if (config.featureFlags.enableConfigRefresh) {
                    storage.write(LatestConfig, config)
                }
                assignments.choosePaywallVariants(config.triggers)

                // Extract entitlements from products and productsV3
                ConfigLogic.extractEntitlementsByProductId(config.products).let {
                    entitlements.addEntitlementsByProductId(it)
                }
                config.productsV3?.let { productsV3 ->
                    ConfigLogic.extractEntitlementsByProductIdFromCrossplatform(productsV3).let {
                        entitlements.addEntitlementsByProductId(it)
                    }
                }

                // Test mode evaluation
                val wasTestMode = testModeManager?.isTestMode == true
                testModeManager?.evaluateTestMode(
                    config = config,
                    bundleId = deviceHelper.bundleId,
                    appUserId = identityManager?.invoke()?.appUserId,
                    aliasId = identityManager?.invoke()?.aliasId,
                    testModeBehavior = options.testModeBehavior,
                )
                val testModeJustActivated = !wasTestMode && testModeManager?.isTestMode == true

                if (testModeManager?.isTestMode == true) {
                    if (testModeJustActivated) {
                        val defaultStatus = testModeManager!!.buildSubscriptionStatus()
                        testModeManager!!.setOverriddenSubscriptionStatus(defaultStatus)
                        entitlements.setSubscriptionStatus(defaultStatus)
                    }
                    effect(FetchTestModeProducts(config, testModeJustActivated))
                } else {
                    if (wasTestMode) {
                        testModeManager?.clearTestModeState()
                        setSubscriptionStatus?.invoke(SubscriptionStatus.Inactive)
                    }
                    ioScope.launch {
                        storeManager.loadPurchasedProducts(entitlements.entitlementsByProductId)
                    }
                }
            })

        /** Check for web entitlements (fire-and-forget). */
        object CheckWebEntitlements : Actions({
            ioScope.launch {
                webPaywallRedeemer().redeem(WebPaywallRedeemer.RedeemType.Existing)
            }
        })

        /**
         * Re-evaluates test mode with the current identity and config.
         */
        data class ReevaluateTestMode(
            val appUserId: String? = null,
            val aliasId: String? = null,
        ) : Actions(
                action@{
                    val resolvedConfig = state.value.config ?: return@action
                    val wasTestMode = testModeManager?.isTestMode == true
                    testModeManager?.evaluateTestMode(
                        config = resolvedConfig,
                        bundleId = deviceHelper.bundleId,
                        appUserId = appUserId ?: identityManager?.invoke()?.appUserId,
                        aliasId = aliasId ?: identityManager?.invoke()?.aliasId,
                        testModeBehavior = options.testModeBehavior,
                    )
                    val isNowTestMode = testModeManager?.isTestMode == true
                    if (wasTestMode && !isNowTestMode) {
                        testModeManager?.clearTestModeState()
                        setSubscriptionStatus?.invoke(SubscriptionStatus.Inactive)
                    } else if (!wasTestMode && isNowTestMode) {
                        effect(FetchTestModeProducts(resolvedConfig, true))
                    }
                },
            )

        /** Fetch test mode products and optionally present the modal. */
        data class FetchTestModeProducts(
            val config: Config,
            val presentModal: Boolean = false,
        ) : Actions(
                action@{
                    val net = fullNetwork ?: return@action
                    val manager = testModeManager ?: return@action

                    net.getSuperwallProducts().fold(
                        onSuccess = { response ->
                            val androidProducts =
                                response.data.filter {
                                    it.platform == SuperwallProductPlatform.ANDROID && it.price != null
                                }
                            manager.setProducts(androidProducts)

                            val productsByFullId =
                                androidProducts.associate { superwallProduct ->
                                    val testProduct = TestStoreProduct(superwallProduct)
                                    superwallProduct.identifier to StoreProduct(testProduct)
                                }
                            manager.setTestProducts(productsByFullId)

                            Logger.debug(
                                LogLevel.info,
                                LogScope.superwallCore,
                                "Test mode: loaded ${androidProducts.size} products",
                            )
                        },
                        onFailure = { error ->
                            Logger.debug(
                                LogLevel.error,
                                LogScope.superwallCore,
                                "Test mode: failed to fetch products - ${error.message}",
                            )
                        },
                    )

                    if (presentModal) {
                        PresentTestModeModal(config).execute.invoke(this@action)
                    }
                },
            )

        /** Present the test mode modal. */
        data class PresentTestModeModal(
            val config: Config,
        ) : Actions(
                action@{
                    val manager = testModeManager ?: return@action
                    val activity =
                        activityTracker?.getCurrentActivity()
                            ?: activityProvider?.getCurrentActivity()
                            ?: activityTracker?.awaitActivity(10.seconds)
                    if (activity == null) {
                        Logger.debug(
                            LogLevel.warn,
                            LogScope.superwallCore,
                            "Test mode modal could not be presented: no activity available. Setting default subscription status.",
                        )
                        with(manager) {
                            val status = buildSubscriptionStatus()
                            setOverriddenSubscriptionStatus(status)
                            entitlements.setSubscriptionStatus(status)
                        }
                        return@action
                    }

                    track(InternalSuperwallEvent.TestModeModal(State.Open))

                    val reason = manager.testModeReason?.description ?: "Test mode activated"
                    val allEntitlements =
                        config.productsV3
                            ?.flatMap { it.entitlements.map { e -> e.id } }
                            ?.distinct()
                            ?.sorted()
                            ?: emptyList()

                    val dashboardBaseUrl =
                        when (options.networkEnvironment) {
                            is com.superwall.sdk.config.options.SuperwallOptions.NetworkEnvironment.Developer -> "https://superwall.dev"
                            else -> "https://superwall.com"
                        }

                    val apiKey = deviceHelper.storage.apiKey
                    val savedSettings = manager.loadSettings()

                    val result =
                        TestModeModal.show(
                            activity = activity,
                            reason = reason,
                            hasPurchaseController = makeHasExternalPurchaseController(),
                            availableEntitlements = allEntitlements,
                            apiKey = apiKey,
                            dashboardBaseUrl = dashboardBaseUrl,
                            savedSettings = savedSettings,
                        )

                    with(manager) {
                        setFreeTrialOverride(result.freeTrialOverride)
                        setEntitlements(result.entitlements)
                        saveSettings()
                        val status = buildSubscriptionStatus()
                        setOverriddenSubscriptionStatus(status)
                        entitlements.setSubscriptionStatus(status)
                    }

                    track(InternalSuperwallEvent.TestModeModal(State.Close))
                },
            )
    }
}
