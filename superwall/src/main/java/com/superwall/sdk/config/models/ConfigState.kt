package com.superwall.sdk.config.models

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.ConfigContext
import com.superwall.sdk.config.ConfigLogic
import com.superwall.sdk.config.options.computedShouldPreload
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.awaitFirstValidConfig
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.into
import com.superwall.sdk.misc.onError
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.misc.then
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.network.awaitUntilNetworkExists
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class ConfigState {
    object None : ConfigState()

    object Retrieving : ConfigState()

    object Retrying : ConfigState()

    data class Retrieved(
        val config: Config,
    ) : ConfigState()

    data class Failed(
        val throwable: Throwable,
    ) : ConfigState()

    /**
     * Pure state transitions. Reducers are `(ConfigState) -> ConfigState` —
     * no side effects. All work (network, storage, tracking) belongs in
     * [Actions].
     */
    internal sealed class Updates(
        override val reduce: (ConfigState) -> ConfigState,
    ) : Reducer<ConfigState> {
        object SetRetrieving : Updates({ Retrieving })

        object SetRetrying : Updates({ Retrying })

        data class SetRetrieved(val config: Config) : Updates({ Retrieved(config) })

        data class SetFailed(val error: Throwable) : Updates({ Failed(error) })

        /** Used by tests to force any state without going through a fetch. */
        data class Set(val state: ConfigState) : Updates({ state })
    }

    /**
     * Side-effecting operations dispatched on the config actor. Actions
     * run sequentially on [com.superwall.sdk.misc.primitives.SequentialActor]
     * and call [com.superwall.sdk.misc.primitives.StateStore.update] with a
     * pure [Updates] reducer when they need to mutate state.
     *
     * Actions that read config state (`Preload*`, `GetAssignments`) expect
     * the caller to have awaited `state.awaitFirstValidConfig()` before
     * dispatching, so they never suspend on state transitions while holding
     * the queue. In practice every public entry point does this, and
     * internal `effect()` calls only fire after a successful fetch.
     */
    internal sealed class Actions(
        override val execute: suspend ConfigContext.() -> Unit,
    ) : TypedAction<ConfigContext> {
        /** Primary fetch pipeline: config + enrichment + device attributes in parallel. */
        object FetchConfig : Actions(exec@{
            val current = state.value
            if (current is Retrieving || current is Retrying) return@exec

            update(Updates.SetRetrieving)

            val oldConfig = storage.read(LatestConfig)
            val status = entitlements.status.value
            val cacheLimit =
                if (status is SubscriptionStatus.Active) 500.milliseconds else 1.seconds

            var isConfigFromCache = false
            var isEnrichmentFromCache = false
            val configRetryCount = AtomicInteger(0)
            var configDuration = 0L

            val configDeferred =
                scope.async {
                    val start = System.currentTimeMillis()
                    (
                        if (oldConfig?.featureFlags?.enableConfigRefresh == true) {
                            try {
                                withTimeout(cacheLimit) {
                                    network
                                        .getConfig {
                                            update(Updates.SetRetrying)
                                            configRetryCount.incrementAndGet()
                                            awaitUtilNetwork()
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
                            network.getConfig {
                                update(Updates.SetRetrying)
                                configRetryCount.incrementAndGet()
                                context.awaitUntilNetworkExists()
                            }
                        }
                    ).also {
                        configDuration = System.currentTimeMillis() - start
                    }
                }

            val enrichmentDeferred =
                scope.async {
                    val cached = storage.read(LatestEnrichment)
                    if (oldConfig?.featureFlags?.enableConfigRefresh == true) {
                        val res =
                            deviceHelper
                                .getEnrichment(0, cacheLimit)
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

            val attributesDeferred = scope.async { factory.makeSessionDeviceAttributes() }

            val (configResultAny, enrichmentResultAny) =
                listOf(configDeferred, enrichmentDeferred).awaitAll()
            val attributes = attributesDeferred.await()
            scope.launch {
                @Suppress("UNCHECKED_CAST")
                track(InternalSuperwallEvent.DeviceAttributes(attributes as HashMap<String, Any>))
            }

            @Suppress("UNCHECKED_CAST")
            val configResult = configResultAny as Either<Config, Throwable>

            @Suppress("UNCHECKED_CAST")
            val enrichmentResult = enrichmentResultAny as Either<Enrichment, Throwable>

            configResult
                .then { config ->
                    scope.launch {
                        track(
                            InternalSuperwallEvent.ConfigRefresh(
                                isCached = isConfigFromCache,
                                buildId = config.buildId,
                                fetchDuration = configDuration,
                                retryCount = configRetryCount.get(),
                            ),
                        )
                    }
                }.then { config -> immediate(ApplyConfig(config)) }
                .then { config ->
                    if (testMode?.isTestMode != true) {
                        scope.launch {
                            webPaywallRedeemer().redeem(WebPaywallRedeemer.RedeemType.Existing)
                        }
                    }
                    config
                }.then { config ->
                    if (testMode?.isTestMode != true &&
                        options.computedShouldPreload(deviceHelper.deviceTier)
                    ) {
                        val productIds = config.paywalls.flatMap { it.productIds }.toSet()
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
                    config
                }.then { config ->
                    update(Updates.SetRetrieved(config))
                }.then {
                    if (isEnrichmentFromCache || enrichmentResult.getThrowable() != null) {
                        scope.launch { deviceHelper.getEnrichment(6, 1.seconds) }
                    }
                }.fold(
                    onSuccess = {
                        // Preload first so cached-config boot stays fast; queue
                        // a follow-up network refresh behind it (matches the old
                        // parallel-launch behavior well enough).
                        effect(PreloadIfEnabled)
                        if (isConfigFromCache) {
                            effect(RefreshConfig())
                        }
                    },
                    onFailure = { e ->
                        e.printStackTrace()
                        update(Updates.SetFailed(e))
                        // Match old behavior: on a non-cached failure, kick a
                        // fresh FetchConfig. Old code did this implicitly via
                        // refreshConfiguration() reading the `config` getter,
                        // which had a side effect of launching fetchConfiguration.
                        // RefreshConfig alone is a no-op here because there's
                        // no retrieved config to refresh. We dispatch via
                        // scope.launch to dodge the Kotlin "self-reference in
                        // nested object initializer" check.
                        if (!isConfigFromCache) {
                            retryFetchConfig()
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
        })

        /** Background refresh after we already have a config. */
        data class RefreshConfig(val force: Boolean = false) : Actions(exec@{
            val oldConfig = state.value.getConfig() ?: return@exec
            if (!force && !oldConfig.featureFlags.enableConfigRefresh) return@exec

            scope.launch { deviceHelper.getEnrichment(0, 1.seconds) }

            val retryCount = AtomicInteger(0)
            val startTime = System.currentTimeMillis()
            val result =
                network.getConfig {
                    retryCount.incrementAndGet()
                    context.awaitUntilNetworkExists()
                }

            result
                .then { newConfig ->
                    paywallManager.resetPaywallRequestCache()
                    val previous = state.value.getConfig()
                    if (previous != null) {
                        paywallPreload.removeUnusedPaywallVCsFromCache(previous, newConfig)
                    }
                    newConfig
                }.then { newConfig ->
                    immediate(ApplyConfig(newConfig))
                    update(Updates.SetRetrieved(newConfig))
                    track(
                        InternalSuperwallEvent.ConfigRefresh(
                            isCached = false,
                            buildId = newConfig.buildId,
                            fetchDuration = System.currentTimeMillis() - startTime,
                            retryCount = retryCount.get(),
                        ),
                    )
                    newConfig
                }.fold(
                    onSuccess = { effect(PreloadIfEnabled) },
                    onFailure = { e ->
                        Logger.debug(
                            logLevel = LogLevel.warn,
                            scope = LogScope.superwallCore,
                            message = "Failed to refresh configuration.",
                            info = null,
                            error = e,
                        )
                    },
                )
        })

        /**
         * Applies a freshly-fetched [config]: persists it, rebuilds triggers,
         * syncs entitlements, and runs test-mode evaluation. Invoked via
         * `immediate(ApplyConfig(config))` from [FetchConfig] and [RefreshConfig]
         * — runs inline (re-entrant) on the actor consumer, so state mutations
         * stay serialized with the surrounding fetch.
         */
        data class ApplyConfig(val config: Config) : Actions({
            storage.write(DisableVerboseEvents, config.featureFlags.disableVerboseEvents)
            if (config.featureFlags.enableConfigRefresh) {
                storage.write(LatestConfig, config)
            }
            setTriggers(ConfigLogic.getTriggersByEventName(config.triggers))
            assignments.choosePaywallVariants(config.triggers)

            ConfigLogic.extractEntitlementsByProductId(config.products).let {
                entitlements.addEntitlementsByProductId(it)
            }
            config.productsV3?.let { productsV3 ->
                ConfigLogic.extractEntitlementsByProductIdFromCrossplatform(productsV3).let {
                    entitlements.addEntitlementsByProductId(it)
                }
            }

            val manager = testMode
            val wasTestMode = manager?.isTestMode == true
            manager?.evaluateTestMode(
                config = config,
                bundleId = deviceHelper.bundleId,
                appUserId = identityManager?.invoke()?.appUserId,
                aliasId = identityManager?.invoke()?.aliasId,
                testModeBehavior = options.testModeBehavior,
            )
            val testModeJustActivated = !wasTestMode && manager?.isTestMode == true

            if (manager?.isTestMode == true) {
                if (testModeJustActivated) {
                    val defaultStatus = manager.buildSubscriptionStatus()
                    manager.setOverriddenSubscriptionStatus(defaultStatus)
                    entitlements.setSubscriptionStatus(defaultStatus)
                }
                scope.launch { activateTestMode(config, testModeJustActivated) }
            } else {
                if (wasTestMode) {
                    manager?.clearTestModeState()
                    setSubscriptionStatus?.invoke(SubscriptionStatus.Inactive)
                }
                scope.launch {
                    storeManager.loadPurchasedProducts(entitlements.entitlementsByProductId)
                }
            }
        })

        /** Preload paywalls when options + device tier allow it. */
        object PreloadIfEnabled : Actions(exec@{
            if (!options.computedShouldPreload(deviceHelper.deviceTier)) return@exec
            val config = state.value.getConfig() ?: return@exec
            paywallPreload.preloadAllPaywalls(config, context)
        })

        /** Unconditional preload — public API entry point. */
        object PreloadAll : Actions(exec@{
            val config = state.value.getConfig() ?: return@exec
            paywallPreload.preloadAllPaywalls(config, context)
        })

        data class PreloadByNames(
            val eventNames: Set<String>,
        ) : Actions(exec@{
            val config = state.value.getConfig() ?: return@exec
            paywallPreload.preloadPaywallsByNames(config, eventNames)
        })

        /** Confirm assignments against the server for all current triggers. */
        object GetAssignments : Actions(exec@{
            val config = state.value.getConfig() ?: return@exec
            config.triggers.takeUnless { it.isEmpty() }?.let { triggers ->
                try {
                    assignments
                        .getAssignments(triggers)
                        .then { effect(PreloadIfEnabled) }
                        .onError { err ->
                            Logger.debug(
                                logLevel = LogLevel.error,
                                scope = LogScope.configManager,
                                message = "Error retrieving assignments.",
                                error = err,
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
        })
    }
}

internal fun ConfigState.getConfig(): Config? =
    when (this) {
        is ConfigState.Retrieved -> config
        else -> null
    }
