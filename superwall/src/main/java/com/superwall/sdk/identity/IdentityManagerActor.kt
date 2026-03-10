package com.superwall.sdk.identity

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.misc.engine.SdkEvent
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.misc.primitives.Effect
import com.superwall.sdk.misc.primitives.Fx
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.sha256MappedToRange
import com.superwall.sdk.storage.AliasId
import com.superwall.sdk.storage.AppUserId
import com.superwall.sdk.storage.Seed
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.UserAttributes
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // Only functions that can update state
    internal sealed class Updates(
        override val applyOn: Fx.(IdentityState) -> IdentityState,
    ) : Reducer<IdentityState>(applyOn) {
        data class Identify(
            val userId: String,
            val options: IdentityOptions?,
        ) : Updates({ state ->
                IdentityLogic.sanitize(userId).takeIf { !it.isNullOrEmpty() }?.let { sanitized ->
                    if (sanitized.isEmpty()) {
                        return@let state
                    }
                    if (sanitized == state.appUserId) return@let state

                    val base =
                        if (state.appUserId != null) {
                            dispatch(SdkState.Updates.FullResetOnIdentify)
                            effect { IdentityEffect.CompleteReset }
                            IdentityState(appInstalledAtString = state.appInstalledAtString)
                        } else {
                            state
                        }

                    persist(AppUserId, sanitized)
                    persist(AliasId, base.aliasId)
                    persist(Seed, base.seed)

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
                    persist(UserAttributes, merged)

                    track(InternalSuperwallEvent.IdentityAlias())

                    defer(until = { it.configReady }) {
                        effect { IdentityEffect.ResolveSeed(sanitized) }
                        effect { IdentityEffect.FetchAssignments }
                        effect { IdentityEffect.ReevaluateTestMode(sanitized, base.aliasId) }
                    }

                    effect { IdentityEffect.CheckWebEntitlements }

                    val waitForAssignments = options?.restorePaywallAssignments == true

                    base.copy(
                        appUserId = sanitized,
                        userAttributes = merged,
                        pending =
                            buildSet {
                                add(Pending.Seed)
                                if (waitForAssignments) add(Pending.Assignments)
                            },
                        isReady = false,
                    )
                } ?: run {
                    log(
                        logLevel = LogLevel.error,
                        scope = LogScope.identityManager,
                        message = "The provided userId was null or empty.",
                    )
                    state
                }
            })

        data class SeedResolved(
            val seed: Int,
        ) : Updates({ state ->
                persist(Seed, seed)
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
                persist(UserAttributes, merged)

                state
                    .copy(
                        seed = seed,
                        userAttributes = merged,
                    ).resolve(Pending.Seed)
            })

        /** Dispatched by ResolveSeed runner when enableUserIdSeed is false or sha256 returns null */
        object SeedSkipped : Updates({ state ->
            state.resolve(Pending.Seed)
        })

        data class AttributesMerged(
            val attrs: Map<String, Any?>,
            val shouldTrackMerge: Boolean = true,
            val shouldNotify: Boolean = false,
        ) : Updates({ state ->
                val merged =
                    IdentityLogic.mergeAttributes(
                        newAttributes = attrs,
                        oldAttributes = state.userAttributes,
                        appInstalledAtString = state.appInstalledAtString,
                    )
                persist(UserAttributes, merged)
                if (shouldTrackMerge) {
                    track(
                        InternalSuperwallEvent.Attributes(
                            appInstalledAtString = state.appInstalledAtString,
                            audienceFilterParams = HashMap(merged),
                        ),
                    )
                }
                if (shouldNotify) {
                    effect { IdentityEffect.NotifyUserChange(merged) }
                }
                state.copy(userAttributes = merged)
            })

        /** Dispatched by FetchAssignments runner on completion (success or failure) */
        object AssignmentsCompleted : Updates({ state ->
            state.resolve(Pending.Assignments)
        })

        /** Replaces IdentityManager.configure() — checks whether to fetch assignments at startup */
        data class Configure(
            val neverCalledStaticConfig: Boolean,
            val isFirstAppOpen: Boolean,
        ) : Updates({ state ->
                val needsAssignments =
                    IdentityLogic.shouldGetAssignments(
                        isLoggedIn = state.isLoggedIn,
                        neverCalledStaticConfig = neverCalledStaticConfig,
                        isFirstAppOpen = isFirstAppOpen,
                    )
                if (needsAssignments) {
                    defer(until = { it.configReady }) {
                        effect { IdentityEffect.FetchAssignments }
                    }
                    state.copy(pending = state.pending + Pending.Assignments)
                } else {
                    state.copy(isReady = true)
                }
            })

        object Ready : Updates({ state ->
            state.copy(isReady = true)
        })

        /** Public reset (Superwall.reset without duringIdentify). Identity-during-identify is a no-op at the facade. */
        object Reset : Updates({ state ->
            val fresh = IdentityState(appInstalledAtString = state.appInstalledAtString)
            persist(AliasId, fresh.aliasId)
            persist(Seed, fresh.seed)
            delete(AppUserId)
            delete(UserAttributes)

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
            persist(UserAttributes, merged)

            fresh.copy(userAttributes = merged, isReady = true)
        })
    }
}

/**
 * Builds initial IdentityState from storage BEFORE the engine starts.
 * This is synchronous — same as the current IdentityManager constructor.
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

    // Only merge identity keys into attributes when values were just generated.
    // If both aliasId and seed came from storage, attributes are already up to date.
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

internal sealed class IdentityEffect(
    val execute: suspend IdentityEffectDeps.(dispatch: (SdkEvent) -> Unit) -> Unit,
) : Effect {
    data class ResolveSeed(
        val userId: String,
    ) : IdentityEffect({ dispatch ->
            val config = configProvider()
            if (config?.featureFlags?.enableUserIdSeed == true) {
                userId.sha256MappedToRange()?.let {
                    dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.SeedResolved(it)))
                } ?: dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.SeedSkipped))
            } else {
                dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.SeedSkipped))
            }
        })

    object FetchAssignments : IdentityEffect({ dispatch ->
        try {
            fetchAssignments?.invoke()
        } finally {
            dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.AssignmentsCompleted))
        }
    })

    object CheckWebEntitlements : IdentityEffect({ dispatch ->
        webPaywallRedeemer?.invoke()?.redeem(WebPaywallRedeemer.RedeemType.Existing)
    })

    data class ReevaluateTestMode(
        val appUserId: String?,
        val aliasId: String,
    ) : IdentityEffect({ dispatch ->
            configProvider()?.let {
                testModeManager?.evaluateTestMode(
                    config = it,
                    bundleId = deviceHelper.bundleId,
                    appUserId = appUserId,
                    aliasId = aliasId,
                )
            }
        })

    data class NotifyUserChange(
        val attributes: Map<String, Any>,
    ) : IdentityEffect(
            { dispatch ->

                notifyUserChange?.invoke(attributes)
                    ?: delegate?.let {
                        withContext(Dispatchers.Main) {
                            it().userAttributesDidChange(attributes)
                        }
                    }
            },
        )

    object CompleteReset : IdentityEffect({ dispatch ->
        completeReset()
    })
}
