package com.superwall.sdk.misc.primitives

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.engine.SdkEvent
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.storage.Storable

internal class Fx {
    internal val pending = mutableListOf<Effect>()

    /**
     * Effects that must complete before the new state is published.
     * Typically storage writes/deletes — so that observers reading storage
     * always see data consistent with the latest state.
     */
    internal val immediate = mutableListOf<Effect>()

    fun <T : Any> persist(
        storable: Storable<T>,
        value: T,
    ) {
        immediate += Effect.Persist(storable, value)
    }

    fun delete(storable: Storable<*>) {
        immediate += Effect.Delete(storable)
    }

    fun track(event: Trackable) {
        pending += Effect.Track(event)
    }

    fun dispatch(event: SdkEvent) {
        pending += Effect.Dispatch(event)
    }

    fun log(
        logLevel: LogLevel,
        scope: LogScope,
        message: String = "",
        info: Map<String, Any>? = null,
        error: Throwable? = null,
    ) {
        Logger.debug(
            logLevel,
            scope,
            message,
            info,
            error,
        )
    }

    fun effect(which: () -> Effect) {
        pending += which()
    }

    /**
     * Declare effects that only run once [until] is satisfied.
     * The engine holds them and checks on every state transition.
     *
     * Usage:
     * ```
     * defer(until = { it.config.isReady }) {
     *     effect { ResolveSeed(userId) }
     *     effect { FetchAssignments }
     * }
     * ```
     */
    fun defer(
        until: (SdkState) -> Boolean,
        block: DeferScope.() -> Unit,
    ) {
        val scope = DeferScope()
        scope.block()
        pending += Effect.Deferred(until, scope.effects)
    }

    class DeferScope {
        internal val effects = mutableListOf<Effect>()

        fun effect(which: () -> Effect) {
            effects += which()
        }
    }

    fun <T, S> fold(
        either: Either<T, Throwable>,
        onSuccess: Fx.(T) -> S,
        onFailure: Fx.(Throwable) -> S,
    ): S =
        when (either) {
            is Either.Success -> onSuccess(either.value)
            is Either.Failure -> onFailure(either.error)
        }
}
