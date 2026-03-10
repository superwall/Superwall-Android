package com.superwall.sdk.misc.primitives

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either.*
import com.superwall.sdk.misc.engine.SdkEvent
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class Engine(
    initial: SdkState,
    private val runEffect: suspend (Effect, dispatch: (SdkEvent) -> Unit) -> Unit,
    scope: CoroutineScope,
    private val enableLogging: Boolean = false,
) {
    private val events = Channel<SdkEvent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SdkState> = _state.asStateFlow()

    // Effects waiting for a state predicate to become true
    private val deferred = mutableListOf<Effect.Deferred>()

    fun dispatch(event: SdkEvent) {
        events.trySend(event)
    }

    init {
        scope.launch {
            for (event in events) {
                if (enableLogging) {
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.superwallCore,
                        message = "Engine: incoming event ${event::class.simpleName}: $event",
                    )
                }

                // 1. Reduce — pure, single-threaded
                val fx = Fx()
                val prev = _state.value

                @Suppress("UNCHECKED_CAST")
                val next =
                    withErrorTracking {
                        (event as Reducer<SdkState>).applyOn(fx, _state.value)
                    }.let { either ->
                        when (either) {
                            is Success -> either.value
                            is Failure -> _state.value // keep current state on error
                        }
                    }
                // 2. Run immediate effects (storage writes) before publishing state
                for (effect in fx.immediate) {
                    withErrorTracking { runEffect(effect, ::dispatch) }
                }

                _state.value = next

                if (enableLogging && prev !== next) {
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.superwallCore,
                        message = "Engine: state transition ${prev::class.simpleName} -> ${next::class.simpleName}",
                    )
                }

                // 3. Process async effects
                if (enableLogging && fx.pending.isNotEmpty()) {
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.superwallCore,
                        message = "Engine: dispatching ${fx.pending.size} effect(s): ${fx.pending.map { it::class.simpleName }}",
                    )
                }
                for (effect in fx.pending) {
                    when (effect) {
                        // Dispatch is synchronous — re-enters the channel immediately
                        is Effect.Dispatch -> dispatch(effect.event)
                        // Deferred — hold until predicate matches
                        is Effect.Deferred -> deferred += effect
                        // Everything else — launch on scope's dispatcher
                        else ->
                            launch {
                                withErrorTracking { runEffect(effect, ::dispatch) }
                            }
                    }
                }

                // 4. Check deferred batches against new state
                if (deferred.isNotEmpty()) {
                    val ready = deferred.filter { it.until(next) }
                    if (ready.isNotEmpty()) {
                        deferred.removeAll(ready.toSet())
                        for (batch in ready) {
                            for (effect in batch.effects) {
                                when (effect) {
                                    is Effect.Dispatch -> dispatch(effect.event)
                                    else ->
                                        launch {
                                            withErrorTracking { runEffect(effect, ::dispatch) }
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
