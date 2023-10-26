package com.superwall.sdk.models.events


@kotlinx.serialization.Serializable
internal data class EventsRequest(val events: List<EventData>)
