package com.superwall.sdk.paywall.presentation.get_presentation_result

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.UserInitiatedEvent
import com.superwall.sdk.paywall.presentation.result.PresentationResult
import java.util.HashMap

suspend fun Superwall.getPresentationResult(
    event: String,
    params: Map<String, Any>? = null
): PresentationResult {
    val event = UserInitiatedEvent.Track(
        rawName = event,
        canImplicitlyTriggerPaywall = false,
        customParameters = HashMap(params ?: emptyMap()),
        isFeatureGatable = false
    )

    return internallyGetPresentationResult(
        event,
        isImplicit = false
    )
}