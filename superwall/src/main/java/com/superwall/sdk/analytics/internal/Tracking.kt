package com.superwall.sdk.analytics.internal

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.paywall.presentation.dismissForNextPaywall
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.internallyPresent
import com.superwall.sdk.paywall.presentation.internal.operators.logErrors
import com.superwall.sdk.paywall.presentation.internal.operators.waitForSubsStatusAndConfig
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.*

suspend fun Superwall.track(event: Trackable): TrackingResult {
    // Get parameters to be sent to the delegate and stored in an event.
    // now with Date
    val eventCreatedAt = Date()
    val parameters = TrackingLogic.processParameters(
        trackableEvent = event,
        appSessionId = dependencyContainer.appSessionManager.appSession.id
    )

    // For a trackable superwall event, send params to delegate
    if (event is TrackableSuperwallEvent) {
        val info = SuperwallEventInfo(
            event = event.superwallEvent,
            params = parameters.delegateParams
        )

        dependencyContainer.delegateAdapter.handleSuperwallEvent(eventInfo = info)

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.events,
            message = "Logged Event",
            info = parameters.eventParams
        )
    }

    val eventData = EventData(
        name = event.rawName,
        parameters = parameters.eventParams,
        createdAt = eventCreatedAt
    )
    dependencyContainer.queue.enqueue(event = eventData)
    dependencyContainer.storage.coreDataManager.saveEventData(eventData, null)

    if (event.canImplicitlyTriggerPaywall) {
        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.handleImplicitTrigger(
                event = event,
                eventData = eventData
            )
        }
    }

    val result = TrackingResult(
        data = eventData,
        parameters = parameters
    )
    return result
}

suspend fun Superwall.handleImplicitTrigger(
    event: Trackable,
    eventData: EventData
) = withContext(Dispatchers.Main) {
    serialTaskManager.addTask {
        internallyHandleImplicitTrigger(event, eventData)
    }
}

private suspend fun Superwall.internallyHandleImplicitTrigger(
    event: Trackable,
    eventData: EventData
) = withContext(Dispatchers.Main) {
    val presentationInfo = PresentationInfo.ImplicitTrigger(eventData)

    var request = dependencyContainer.makePresentationRequest(
        presentationInfo = presentationInfo,
        isPaywallPresented = isPaywallPresented,
        type = PresentationRequestType.Presentation
    )

    // TODO: https://linear.app/superwall/issue/SW-2414/[android]-wait-for-sub-status
    try {
        waitForSubsStatusAndConfig(request, null)
    } catch (e: Exception) {
        logErrors(request, e)
        return@withContext
    }

    val outcome = TrackingLogic.canTriggerPaywall(
        event,
        dependencyContainer.configManager.triggersByEventName.keys.toSet(),
        paywallViewController
    )

    var statePublisher = MutableSharedFlow<PaywallState>()

    when (outcome) {
        TrackingLogic.ImplicitTriggerOutcome.DeepLinkTrigger -> {
            dismiss()
        }
        TrackingLogic.ImplicitTriggerOutcome.ClosePaywallThenTriggerPaywall -> {
            val lastPresentationItems = presentationItems.getLast() ?: return@withContext
            dismissForNextPaywall()
            statePublisher = lastPresentationItems.statePublisher
        }
        TrackingLogic.ImplicitTriggerOutcome.TriggerPaywall -> {}
        TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall -> {
            return@withContext
        }

        else -> {}
    }

    request.flags.isPaywallPresented = isPaywallPresented

    internallyPresent(request, statePublisher)
}