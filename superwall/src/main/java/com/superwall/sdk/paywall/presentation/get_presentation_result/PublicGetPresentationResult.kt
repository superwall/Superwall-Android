package com.superwall.sdk.paywall.presentation.get_presentation_result

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingLogic
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.UserInitiatedEvent
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.result.PresentationResult
import com.superwall.sdk.utilities.withErrorTrackingAsync
import java.util.Date
import java.util.HashMap

suspend fun Superwall.getPresentationResult(
    event: String,
    params: Map<String, Any>? = null,
): Result<PresentationResult> =
    withErrorTrackingAsync {
        val event =
            UserInitiatedEvent.Track(
                rawName = event,
                canImplicitlyTriggerPaywall = false,
                customParameters = HashMap(params ?: emptyMap()),
                isFeatureGatable = false,
            )

        return@withErrorTrackingAsync internallyGetPresentationResult(
            event,
            isImplicit = false,
        )
    }.toResult()

internal suspend fun Superwall.internallyGetPresentationResult(
    event: Trackable,
    isImplicit: Boolean,
): PresentationResult {
    val eventCreatedAt = Date()

    val parameters =
        TrackingLogic.processParameters(
            trackableEvent = event,
            appSessionId = dependencyContainer.appSessionManager.appSession.id,
        )

    val eventData =
        EventData(
            name = event.rawName,
            parameters = parameters.audienceFilterParams,
            createdAt = eventCreatedAt,
        )

    val presentationRequest =
        dependencyContainer.makePresentationRequest(
            PresentationInfo.ExplicitTrigger(eventData), // Assuming a similar structure in Kotlin
            isDebuggerLaunched = false,
            isPaywallPresented = false,
            type =
                if (isImplicit) {
                    PresentationRequestType.GetImplicitPresentationResult
                } else {
                    PresentationRequestType.GetPresentationResult
                },
        )

    return getPresentationResult(presentationRequest)
}
