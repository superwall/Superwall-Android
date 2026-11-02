package com.superwall.sdk.analytics

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * The flow behind `Superwall.placements`: every Superwall event tracking hands to the
 * delegate is emitted here too.
 */
internal class SuperwallEventBus(
    private val scope: CoroutineScope,
) {
    private val _placements: MutableSharedFlow<SuperwallEventInfo> =
        MutableSharedFlow(
            0,
            extraBufferCapacity = 64 * 4,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    val placements: SharedFlow<SuperwallEventInfo> = _placements

    /** Emits [info] without blocking the caller; a full buffer suspends only the launched emit. */
    fun emit(info: SuperwallEventInfo) {
        scope.launch {
            _placements.emit(info)
        }
    }
}
