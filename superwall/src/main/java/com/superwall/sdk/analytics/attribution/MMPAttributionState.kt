package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.config.getConfig
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

/**
 * Where this launch's install match is.
 */
internal data class MMPAttributionState(
    val match: Match = Match.None,
) {
    sealed class Match {
        /** No install match this launch. */
        object None : Match()

        /** This launch will match, but the match isn't set up yet. */
        object Pending : Match()

        /** Set up and waiting for config to turn the MMP on. */
        data class Waiting(
            val start: () -> Job?,
        ) : Match()

        /** Skipped because the app opted out of tracking. Tried again when it opts back in. */
        data class Skipped(
            val start: () -> Job?,
        ) : Match()

        object Running : Match()

        object Finished : Match()
    }

    /** `true` from when this launch's install match is set up until it finishes or is skipped. */
    val isMatchPending: Boolean
        get() = match is Match.Pending || match is Match.Waiting || match is Match.Running

    internal sealed class Updates(
        override val reduce: (MMPAttributionState) -> MMPAttributionState,
    ) : Reducer<MMPAttributionState> {
        object MarkPending : Updates({ state ->
            if (state.match is Match.None) state.copy(match = Match.Pending) else state
        })

        data class SetUp(
            val start: () -> Job?,
        ) : Updates({ state ->
                when (state.match) {
                    is Match.Running, is Match.Finished -> state
                    else -> state.copy(match = Match.Waiting(start))
                }
            })

        data class Skip(
            val start: () -> Job?,
        ) : Updates({ state -> state.copy(match = Match.Skipped(start)) })

        object Start : Updates({ state -> state.copy(match = Match.Running) })

        object Finish : Updates({ state -> state.copy(match = Match.Finished) })
    }

    internal sealed class Actions(
        override val execute: suspend MMPAttributionContext.() -> Unit,
    ) : TypedAction<MMPAttributionContext> {
        /**
         * Sets up the match and starts it once config turns the MMP on, which may be straight
         * away. The wait for config runs off the queue so it doesn't hold up other actions.
         */
        data class MatchOnceEnabled(
            val start: () -> Job?,
        ) : Actions({
                update(Updates.SetUp(start))
                sideEffect {
                    configState.first { it.getConfig()?.isMmpEnabled == true }
                    effect(StartIfEnabled)
                }
            })

        /**
         * Starts this install's match if config has the MMP on and it hasn't started yet.
         * A null job from `start` means it skipped the match because the app has opted out
         * of tracking.
         */
        object StartIfEnabled : Actions(
            exec@{
                if (!isMmpEnabled) return@exec
                val start =
                    when (val match = state.value.match) {
                        is Match.Waiting -> match.start
                        is Match.Skipped -> match.start
                        else -> return@exec
                    }
                val job = start()
                if (job == null) {
                    // Don't hold paywalls up while opted out.
                    update(Updates.Skip(start))
                    return@exec
                }
                update(Updates.Start)
                sideEffect {
                    job.join()
                    update(Updates.Finish)
                }
            },
        )
    }
}
