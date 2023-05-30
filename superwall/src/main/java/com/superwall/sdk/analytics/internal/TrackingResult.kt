package com.superwall.sdk.analytics.internal

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.models.events.EventData

data class TrackingResult(
    var data: EventData,
    val parameters: TrackingParameters
) {
    companion object {
        fun stub(): TrackingResult {
            return TrackingResult(
                data = EventData.stub(),
                parameters = TrackingParameters.stub()
            )
        }
    }
}
