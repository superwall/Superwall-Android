package com.superwall.sdk.config

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.misc.primitives.Fx
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.LatestConfig

data class ConfigSlice(
    val phase: Phase = Phase.None,
    val triggersByEventName: Map<String, Trigger> = emptyMap(),
    val unconfirmedAssignments: Map<ExperimentID, Experiment.Variant> = emptyMap(),
) {
    sealed class Phase {
        object None : Phase()
        object Retrieving : Phase()
        object Retrying : Phase()
        data class Retrieved(val config: Config) : Phase()
        data class Failed(val error: Throwable) : Phase()
    }

    val config: Config? get() = (phase as? Phase.Retrieved)?.config
    val isRetrieved: Boolean get() = phase is Phase.Retrieved

    internal sealed class Updates(
        override val applyOn: Fx.(ConfigSlice) -> ConfigSlice,
    ) : Reducer<ConfigSlice>(applyOn) {

        /** Guards against duplicate fetches. Sets phase to Retrieving and kicks off the fetch effect. */
        object FetchRequested : Updates({ state ->
            if (state.phase is Phase.Retrieving) {
                state // already fetching
            } else {
                effect { ConfigEffect.FetchConfig }
                state.copy(phase = Phase.Retrieving)
            }
        })

        /** Network retry happening. */
        object Retrying : Updates({ state ->
            state.copy(phase = Phase.Retrying)
        })

        /**
         * Config fetched successfully. Pure processing happens here; impure goes to effects.
         * Maps to: processConfig() pure parts + configState.update { Retrieved }.
         */
        data class ConfigRetrieved(
            val config: Config,
            val isCached: Boolean,
            val fetchDuration: Long,
            val retryCount: Int,
            val isEnrichmentCached: Boolean,
            val enrichmentFailed: Boolean,
        ) : Updates({ state ->
            val triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)

            persist(DisableVerboseEvents, config.featureFlags.disableVerboseEvents)
            if (config.featureFlags.enableConfigRefresh) {
                persist(LatestConfig, config)
            }

            track(
                InternalSuperwallEvent.ConfigRefresh(
                    isCached = isCached,
                    buildId = config.buildId,
                    fetchDuration = fetchDuration,
                    retryCount = retryCount,
                ),
            )

            // Signal config ready to the top-level SdkState
            dispatch(SdkState.Updates.ConfigReady)

            // Side effects for impure work
            effect {
                ConfigEffect.ProcessConfigSideEffects(
                    config = config,
                    isCached = isCached,
                    isEnrichmentCached = isEnrichmentCached,
                    enrichmentFailed = enrichmentFailed,
                )
            }

            state.copy(
                phase = Phase.Retrieved(config),
                triggersByEventName = triggersByEventName,
            )
        })

        /** Config fetch failed. */
        data class ConfigFailed(
            val error: Throwable,
            val wasConfigCached: Boolean,
        ) : Updates({ state ->
            track(InternalSuperwallEvent.ConfigFail(error.message ?: "Unknown error"))
            log(LogLevel.error, LogScope.superwallCore, "Failed to Fetch Configuration", error = error)

            if (!wasConfigCached) {
                effect { ConfigEffect.RefreshConfiguration(force = false) }
            }

            state.copy(phase = Phase.Failed(error))
        })

        /** Retry fetch when config getter is called in Failed state. */
        object RetryFetch : Updates({ state ->
            if (state.phase is Phase.Failed) {
                effect { ConfigEffect.FetchConfig }
                state.copy(phase = Phase.Retrieving)
            } else {
                state
            }
        })

        /** Assignments updated after choose or fetch from server. */
        data class AssignmentsUpdated(
            val unconfirmed: Map<ExperimentID, Experiment.Variant>,
            val confirmed: Map<ExperimentID, Experiment.Variant>,
        ) : Updates({ state ->
            effect { ConfigEffect.SaveConfirmedAssignments(confirmed) }
            effect { ConfigEffect.PreloadPaywalls }
            state.copy(unconfirmedAssignments = unconfirmed)
        })

        /** Confirms a single assignment. */
        data class ConfirmAssignment(
            val assignment: ConfirmableAssignment,
            val confirmedAssignments: Map<ExperimentID, Experiment.Variant>,
        ) : Updates({ state ->
            val outcome = ConfigLogic.move(
                assignment,
                state.unconfirmedAssignments,
                confirmedAssignments,
            )
            effect { ConfigEffect.SaveConfirmedAssignments(outcome.confirmed) }
            effect { ConfigEffect.PostAssignmentConfirmation(assignment) }
            state.copy(unconfirmedAssignments = outcome.unconfirmed)
        })

        /** Reset: clears unconfirmed, re-chooses variants. */
        data class Reset(
            val confirmedAssignments: Map<ExperimentID, Experiment.Variant>,
        ) : Updates({ state ->
            val config = state.config
            if (config != null) {
                val outcome = ConfigLogic.chooseAssignments(
                    fromTriggers = config.triggers,
                    confirmedAssignments = confirmedAssignments,
                )
                effect { ConfigEffect.SaveConfirmedAssignments(outcome.confirmed) }
                effect { ConfigEffect.PreloadPaywalls }
                state.copy(unconfirmedAssignments = outcome.unconfirmed)
            } else {
                state
            }
        })

        /** Background config refresh completed successfully. */
        data class ConfigRefreshed(
            val config: Config,
            val oldConfig: Config?,
            val fetchDuration: Long,
            val retryCount: Int,
        ) : Updates({ state ->
            val triggersByEventName = ConfigLogic.getTriggersByEventName(config.triggers)

            persist(DisableVerboseEvents, config.featureFlags.disableVerboseEvents)
            if (config.featureFlags.enableConfigRefresh) {
                persist(LatestConfig, config)
            }

            track(
                InternalSuperwallEvent.ConfigRefresh(
                    isCached = false,
                    buildId = config.buildId,
                    fetchDuration = fetchDuration,
                    retryCount = retryCount,
                ),
            )

            dispatch(SdkState.Updates.ConfigReady)

            effect { ConfigEffect.HandleConfigRefreshSideEffects(config, oldConfig) }

            state.copy(
                phase = Phase.Retrieved(config),
                triggersByEventName = triggersByEventName,
            )
        })
    }
}
