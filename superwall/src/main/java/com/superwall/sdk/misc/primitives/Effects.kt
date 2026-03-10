package com.superwall.sdk.misc.primitives

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.misc.engine.SdkEvent
import com.superwall.sdk.misc.engine.SdkState
import com.superwall.sdk.storage.Storable

interface Effect {
    data class Persist(
        val storable: Storable<*>,
        val value: Any,
    ) : Effect

    data class Delete(
        val storable: Storable<*>,
    ) : Effect

    data class Track(
        val event: Trackable,
    ) : Effect

    data class Dispatch(
        val event: SdkEvent,
    ) : Effect

    data class Log(
        val logLevel: LogLevel,
        val scope: LogScope,
        val message: String = "",
        val info: Map<String, Any>? = null,
        val error: Throwable? = null,
    ) : Effect

    /**
     * A batch of effects that wait for a state predicate before executing.
     * The engine holds deferred batches and checks them after every state
     * transition — when [until] returns true, all [effects] are launched.
     *
     * This avoids suspended coroutines waiting for state (e.g. "await config")
     * and keeps the effect system declarative.
     */
    data class Deferred(
        val until: (SdkState) -> Boolean,
        val effects: List<Effect>,
    ) : Effect
}
