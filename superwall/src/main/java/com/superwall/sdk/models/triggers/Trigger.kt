package com.superwall.sdk.models.triggers

import kotlinx.serialization.Serializable

@Serializable
data class Trigger(
    val eventName: String,
    val rules: List<TriggerRule>
) {
    companion object {
        fun stub() = Trigger(
            eventName = "an_event",
            rules = emptyList()
        )
    }
}
