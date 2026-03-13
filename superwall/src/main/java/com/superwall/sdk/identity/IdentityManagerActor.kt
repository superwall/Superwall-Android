package com.superwall.sdk.identity

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.Assignments
import com.superwall.sdk.config.SdkConfigState
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.misc.sha256MappedToRange
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.storage.AliasId
import com.superwall.sdk.storage.AppUserId
import com.superwall.sdk.storage.DidTrackFirstSeen
import com.superwall.sdk.storage.Seed
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.UserAttributes
import com.superwall.sdk.web.WebPaywallRedeemer

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
            val restoreAssignments: Boolean,
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
                        pending = buildSet {
                            add(Pending.Seed)
                            if (restoreAssignments) add(Pending.Assignments)
                        },
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
        /**
         * Dispatched by the config slice after config is successfully retrieved.
         * Receives the config directly — no cross-slice awaiting needed.
         */
        data class Configure(
            val config: Config,
            val neverCalledStaticConfig: Boolean,
        ) : Actions({
                val isFirstAppOpen = !(storage.read(DidTrackFirstSeen) ?: false)
                val needsAssignments =
                    IdentityLogic.shouldGetAssignments(
                        isLoggedIn = actor.state.value.isLoggedIn,
                        neverCalledStaticConfig = neverCalledStaticConfig,
                        isFirstAppOpen = isFirstAppOpen,
                    )
                update(Updates.Configure(needsAssignments = needsAssignments))
                if (needsAssignments) {
                    effect(FetchAssignments)
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
                } else if (sanitized != state.value.appUserId) {
                    val wasLoggedIn = state.value.appUserId != null

                    // If switching users, reset other managers BEFORE updating state
                    // so storage.reset() doesn't wipe the new IDs
                    if (wasLoggedIn) {
                        completeReset()
                        immediate(Reset)
                    }

                    // Update state (pure) — persistence handled by interceptor
                    update(Updates.Identify(sanitized, options?.restorePaywallAssignments == true))

                    val newState = state.value
                    immediate(
                        IdentityChanged(
                            sanitized,
                            newState.aliasId,
                            options?.restorePaywallAssignments,
                        ),
                    )
                }
            })

        data class IdentityChanged(
            val id: String,
            val alias: String,
            val restoreAssignments: Boolean?,
        ) : Actions({
                // Track
                val id = id
                track(InternalSuperwallEvent.IdentityAlias())

                // Fire-and-forget sub-actions
                effect(ResolveSeed(id))
                effect(CheckWebEntitlements)
                sdkContext.effect(
                    SdkConfigState.Actions.ReevaluateTestMode(
                        id,
                        alias,
                    ),
                )

                // Fetch assignments — inline if restoring, fire-and-forget otherwise
                if (restoreAssignments == true) {
                    immediate(FetchAssignments)
                } else {
                    effect(FetchAssignments)
                }
            })

        data class ResolveSeed(
            val userId: String,
        ) : Actions({
                try {
                    val config = sdkContext.state.awaitConfig()
                    if (config != null && config.featureFlags.enableUserIdSeed) {
                        userId.sha256MappedToRange()?.let { mapped ->
                            update(Updates.SeedResolved(mapped))
                        } ?: update(Updates.SeedSkipped)
                    } else {
                        update(Updates.SeedSkipped)
                    }
                } catch (_: Exception) {
                    update(Updates.SeedSkipped)
                }
            })

        object FetchAssignments : Actions({
            try {
                sdkContext.immediate(SdkConfigState.Actions.FetchAssignments)
            } finally {
                update(Updates.AssignmentsCompleted)
            }
        })

        object CheckWebEntitlements : Actions({
            webPaywallRedeemer?.invoke()?.redeem(WebPaywallRedeemer.RedeemType.Existing)
        })

        data class MergeAttributes(
            val attrs: Map<String, Any?>,
            val shouldTrackMerge: Boolean = true,
            val shouldNotify: Boolean = false,
        ) : Actions({
                update(Updates.AttributesMerged(attrs))
                if (shouldTrackMerge) {
                    val current = actor.state.value
                    track(
                        InternalSuperwallEvent.Attributes(
                            appInstalledAtString = current.appInstalledAtString,
                            audienceFilterParams = HashMap(current.userAttributes),
                        ),
                    )
                }
                if (shouldNotify) {
                    effect(NotifyUserChange(actor.state.value.userAttributes))
                }
            })

        data class NotifyUserChange(
            val attributes: Map<String, Any>,
        ) : Actions({
                notifyUserChange?.invoke(attributes)
            })

        object Reset : Actions({
            update(Updates.Reset)
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
