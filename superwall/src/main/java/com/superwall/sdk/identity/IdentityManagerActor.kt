package com.superwall.sdk.identity

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.misc.sha256MappedToRange
import com.superwall.sdk.storage.AliasId
import com.superwall.sdk.storage.AppUserId
import com.superwall.sdk.storage.Seed
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.UserAttributes
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.flow.first

internal object Keys {
    const val APP_USER_ID = "appUserId"
    const val ALIAS_ID = "aliasId"
    const val SEED = "seed"
}

enum class Pending { Seed, Assignments }

data class IdentityState(
    val appUserId: String? = null,
    val aliasId: String = IdentityLogic.generateAlias(),
    val seed: Int = IdentityLogic.generateSeed(),
    val userAttributes: Map<String, Any> = emptyMap(),
    val pending: Set<Pending> = emptySet(),
    val isReady: Boolean = false,
    val appInstalledAtString: String = "",
) {
    val userId: String get() = appUserId ?: aliasId

    val isLoggedIn: Boolean get() = appUserId != null

    val enrichedAttributes: Map<String, Any>
        get() =
            userAttributes.toMutableMap().apply {
                put(Keys.APP_USER_ID, userId)
                put(Keys.ALIAS_ID, aliasId)
            }

    fun resolve(item: Pending): IdentityState {
        val next = pending - item
        return if (next.isEmpty()) copy(pending = next, isReady = true) else copy(pending = next)
    }

    // -----------------------------------------------------------------------
    // Pure state mutations — (IdentityState) -> IdentityState, nothing else
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (IdentityState) -> IdentityState,
    ) : Reducer<IdentityState> {
        data class Identify(
            val userId: String,
        ) : Updates({ state ->
                val sanitized = IdentityLogic.sanitize(userId)
                if (sanitized.isNullOrEmpty() || sanitized == state.appUserId) {
                    state
                } else {
                    val base =
                        if (state.appUserId != null) {
                            // Switching users — start fresh identity
                            IdentityState(appInstalledAtString = state.appInstalledAtString)
                        } else {
                            state
                        }

                    val merged =
                        IdentityLogic.mergeAttributes(
                            newAttributes =
                                mapOf(
                                    Keys.APP_USER_ID to sanitized,
                                    Keys.ALIAS_ID to base.aliasId,
                                    Keys.SEED to base.seed,
                                ),
                            oldAttributes = base.userAttributes,
                            appInstalledAtString = state.appInstalledAtString,
                        )

                    base.copy(
                        appUserId = sanitized,
                        userAttributes = merged,
                        pending =
                            setOf(Pending.Seed, Pending.Assignments),
                        isReady = false,
                    )
                }
            })

        data class SeedResolved(
            val seed: Int,
        ) : Updates({ state ->
                val merged =
                    IdentityLogic.mergeAttributes(
                        newAttributes =
                            mapOf(
                                Keys.APP_USER_ID to state.userId,
                                Keys.ALIAS_ID to state.aliasId,
                                Keys.SEED to seed,
                            ),
                        oldAttributes = state.userAttributes,
                        appInstalledAtString = state.appInstalledAtString,
                    )

                state
                    .copy(seed = seed, userAttributes = merged)
                    .resolve(Pending.Seed)
            })

        object SeedSkipped : Updates({ state ->
            state.resolve(Pending.Seed)
        })

        data class AttributesMerged(
            val attrs: Map<String, Any?>,
        ) : Updates({ state ->
                val merged =
                    IdentityLogic.mergeAttributes(
                        newAttributes = attrs,
                        oldAttributes = state.userAttributes,
                        appInstalledAtString = state.appInstalledAtString,
                    )
                state.copy(userAttributes = merged)
            })

        object AssignmentsCompleted : Updates({ state ->
            state.resolve(Pending.Assignments)
        })

        data class Configure(
            val needsAssignments: Boolean,
        ) : Updates({ state ->
                if (needsAssignments) {
                    state.copy(pending = state.pending + Pending.Assignments)
                } else {
                    state.copy(isReady = true)
                }
            })

        object Reset : Updates({ state ->
            val fresh = IdentityState(appInstalledAtString = state.appInstalledAtString)
            val merged =
                IdentityLogic.mergeAttributes(
                    newAttributes =
                        mapOf(
                            Keys.ALIAS_ID to fresh.aliasId,
                            Keys.SEED to fresh.seed,
                        ),
                    oldAttributes = emptyMap(),
                    appInstalledAtString = state.appInstalledAtString,
                )
            fresh.copy(userAttributes = merged, isReady = true)
        })
    }

    // -----------------------------------------------------------------------
    // Async work — actions have full access to IdentityContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend IdentityContext.() -> Unit,
    ) : TypedAction<IdentityContext> {
        data class Configure(
            val neverCalledStaticConfig: Boolean,
            val isFirstAppOpen: Boolean,
        ) : Actions({
                if (neverCalledStaticConfig) {
                    // Static config was never called — check if assignments are needed,
                    // and if so, wait for config to become available first.
                    val needsAssignments =
                        IdentityLogic.shouldGetAssignments(
                            isLoggedIn = actor.state.value.isLoggedIn,
                            neverCalledStaticConfig = true,
                            isFirstAppOpen = isFirstAppOpen,
                        )
                    if (needsAssignments) {
                        configManager.hasConfig.first()
                        actor.update(Updates.Configure(needsAssignments = true))
                        effect(FetchAssignments)
                    } else {
                        // No assignments needed — mark identity as ready immediately
                        actor.update(Updates.Configure(needsAssignments = false))
                    }
                } else {
                    val needsAssignments =
                        IdentityLogic.shouldGetAssignments(
                            isLoggedIn = actor.state.value.isLoggedIn,
                            neverCalledStaticConfig = neverCalledStaticConfig,
                            isFirstAppOpen = isFirstAppOpen,
                        )

                    if (needsAssignments) {
                        actor.update(Updates.Configure(needsAssignments = true))
                        effect(FetchAssignments)
                    } else {
                        actor.update(Updates.Configure(needsAssignments = false))
                    }
                }
            })

        data class Identify(
            val userId: String,
            val options: IdentityOptions?,
        ) : Actions({
                val sanitized = IdentityLogic.sanitize(userId)
                if (sanitized.isNullOrEmpty()) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.identityManager,
                        message = "The provided userId was null or empty.",
                    )
                } else if (sanitized != actor.state.value.appUserId) {
                    val wasLoggedIn = actor.state.value.appUserId != null

                    // If switching users, reset other managers BEFORE updating state
                    // so storage.reset() doesn't wipe the new IDs
                    if (wasLoggedIn) {
                        completeReset()
                    }

                    // Update state (pure)
                    actor.update(Updates.Identify(sanitized))

                    // Side effects: persist new IDs (after reset, so they aren't wiped)
                    val newState = actor.state.value
                    persist(AppUserId, sanitized)
                    persist(AliasId, newState.aliasId)
                    persist(Seed, newState.seed)
                    persist(UserAttributes, newState.userAttributes)

                    // Track
                    track(InternalSuperwallEvent.IdentityAlias())

                    // Fire-and-forget sub-actions
                    effect(ResolveSeed(sanitized))
                    effect(CheckWebEntitlements)
                    effect(
                        ReevaluateTestMode(
                            appUserId = sanitized,
                            aliasId = newState.aliasId,
                        ),
                    )

                    // Fetch assignments — inline if restoring, fire-and-forget otherwise
                    if (options?.restorePaywallAssignments == true) {
                        FetchAssignments.execute.invoke(this)
                    } else {
                        effect(FetchAssignments)
                    }
                }
            })

        data class ResolveSeed(
            val userId: String,
        ) : Actions({
                try {
                    val config = configManager.hasConfig.first()
                    if (config.featureFlags.enableUserIdSeed) {
                        userId.sha256MappedToRange()?.let { mapped ->
                            actor.update(Updates.SeedResolved(mapped))
                            persist(Seed, mapped)
                            persist(UserAttributes, actor.state.value.userAttributes)
                        } ?: actor.update(Updates.SeedSkipped)
                    } else {
                        actor.update(Updates.SeedSkipped)
                    }
                } catch (_: Exception) {
                    actor.update(Updates.SeedSkipped)
                }
            })

        object FetchAssignments : Actions({
            try {
                configState.dispatchAwait(configCtx, SdkConfigState.Actions.FetchAssignments)
            } finally {
                actor.update(Updates.AssignmentsCompleted)
            }
        })

        object CheckWebEntitlements : Actions({
            webPaywallRedeemer?.invoke()?.redeem(WebPaywallRedeemer.RedeemType.Existing)
        })

        data class ReevaluateTestMode(
            val appUserId: String?,
            val aliasId: String,
        ) : Actions({
                configProvider()?.let {
                    configManager.reevaluateTestMode(
                        config = it,
                        appUserId = appUserId,
                        aliasId = aliasId,
                    )
                }
            })

        data class MergeAttributes(
            val attrs: Map<String, Any?>,
            val shouldTrackMerge: Boolean = true,
            val shouldNotify: Boolean = false,
        ) : Actions({
                actor.update(Updates.AttributesMerged(attrs))
                val merged = actor.state.value.userAttributes
                persist(UserAttributes, merged)
                if (shouldTrackMerge) {
                    track(
                        InternalSuperwallEvent.Attributes(
                            appInstalledAtString = actor.state.value.appInstalledAtString,
                            audienceFilterParams = HashMap(merged),
                        ),
                    )
                }
                if (shouldNotify) {
                    effect(NotifyUserChange(merged))
                }
            })

        data class NotifyUserChange(
            val attributes: Map<String, Any>,
        ) : Actions({
                notifyUserChange?.invoke(attributes)
            })

        object Reset : Actions({
            actor.update(Updates.Reset)
            val fresh = actor.state.value
            persist(AliasId, fresh.aliasId)
            persist(Seed, fresh.seed)
            delete(AppUserId)
            persist(UserAttributes, fresh.userAttributes)
        })

        object CompleteReset : Actions({
            completeReset()
        })
    }
}

/**
 * Builds initial IdentityState from storage BEFORE the actor starts.
 * This is synchronous — same as the old IdentityManager constructor.
 */
internal fun createInitialIdentityState(
    storage: Storage,
    appInstalledAtString: String,
): IdentityState {
    val storedAliasId = storage.read(AliasId)
    val storedSeed = storage.read(Seed)

    val aliasId =
        storedAliasId ?: IdentityLogic.generateAlias().also {
            storage.write(AliasId, it)
        }
    val seed =
        storedSeed ?: IdentityLogic.generateSeed().also {
            storage.write(Seed, it)
        }
    val appUserId = storage.read(AppUserId)
    val userAttributes = storage.read(UserAttributes) ?: emptyMap()

    val needsMerge = storedAliasId == null || storedSeed == null
    val finalAttributes =
        if (needsMerge) {
            val enriched =
                IdentityLogic.mergeAttributes(
                    newAttributes =
                        buildMap {
                            put(Keys.ALIAS_ID, aliasId)
                            put(Keys.SEED, seed)
                            appUserId?.let { put(Keys.APP_USER_ID, it) }
                        },
                    oldAttributes = userAttributes,
                    appInstalledAtString = appInstalledAtString,
                )
            if (enriched != userAttributes) {
                storage.write(UserAttributes, enriched)
            }
            enriched
        } else {
            userAttributes
        }

    return IdentityState(
        appUserId = appUserId,
        aliasId = aliasId,
        seed = seed,
        userAttributes = finalAttributes,
        isReady = false,
        appInstalledAtString = appInstalledAtString,
    )
}
