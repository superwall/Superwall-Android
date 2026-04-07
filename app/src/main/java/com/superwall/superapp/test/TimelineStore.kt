package com.superwall.superapp.test

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry of named [EventTimeline] instances.
 * The app delegate records into a "Live" timeline.
 * Each UITestInfo registers its timeline here when a test runs.
 */
object TimelineStore {
    private val timelines = ConcurrentHashMap<String, EventTimeline>()
    private val _timelinesFlow = MutableStateFlow<Map<String, EventTimeline>>(emptyMap())

    /** Observable snapshot of all registered timelines. */
    val timelinesFlow: StateFlow<Map<String, EventTimeline>> = _timelinesFlow.asStateFlow()

    fun register(name: String, timeline: EventTimeline) {
        timelines[name] = timeline
        _timelinesFlow.value = timelines.toMap()
    }

    fun remove(name: String) {
        timelines.remove(name)
        _timelinesFlow.value = timelines.toMap()
    }

    fun get(name: String): EventTimeline? = timelines[name]

    fun all(): Map<String, EventTimeline> = timelines.toMap()

    fun clear() {
        timelines.clear()
        _timelinesFlow.value = emptyMap()
    }
}
