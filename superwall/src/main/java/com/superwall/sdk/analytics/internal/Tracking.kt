package com.superwall.sdk.analytics.internal

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.dismissForNextPaywall
import com.superwall.sdk.paywall.presentation.internal.internallyPresent
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*

suspend fun Superwall.track(event: Trackable): TrackingResult {
    // Get parameters to be sent to the delegate and stored in an event.
    // now with Date
    val eventCreatedAt = Date()
    val parameters = TrackingLogic.processParameters(
        trackableEvent = event,
        eventCreatedAt = eventCreatedAt,
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
        parameters = JSONObject(parameters.eventParams),
        createdAt = eventCreatedAt
    )
    dependencyContainer.queue.enqueue(event = eventData)
    dependencyContainer.storage.coreDataManager.saveEventData(eventData, null)

    if (event.canImplicitlyTriggerPaywall) {
        CoroutineScope(Dispatchers.Default).launch {
            Superwall.instance.handleImplicitTrigger(
                forEvent = event,
                withData = eventData
            )
        }
    }

    val result = TrackingResult(
        data = eventData,
        parameters = parameters
    )
    return result
}

//@MainCoroutineDispatcher
suspend fun Superwall.handleImplicitTrigger(
    forEvent: Trackable,
    withData: EventData
) {
    println("!! handleImplicitTrigger 1: ${forEvent.rawName}")

    val event = forEvent
    val eventData = withData

    // Should block until identity is available

    println("!! handleImplicitTrigger 1.1 - awaiting identity ${forEvent.rawName} ${Thread.currentThread().name}")
    dependencyContainer.identityManager.hasIdentity.filter { it }.first()
    println("!! handleImplicitTrigger 1.1 - confirmed identity ${forEvent.rawName} ${Thread.currentThread().name}")

    println("!! handleImplicitTrigger 1.5")

//     TODO: Divergence from iOS -> waiting for config
//    dependencyContainer.configManager.hasConfig.first()


    println("!! handleImplicitTrigger 2")

    val presentationInfo: PresentationInfo = PresentationInfo.ImplicitTrigger(eventData = eventData)

    val outcome = TrackingLogic.canTriggerPaywall(
        event,
        triggers = dependencyContainer.configManager.triggersByEventName.keys.toSet(),
        paywallViewController = paywallViewController
    )

    println("!! handleImplicitTrigger 3b $forEvent")
    println("!! handleImplicitTrigger 3a ${dependencyContainer.configManager.triggersByEventName.keys}")
    println("!! handleImplicitTrigger 3 $outcome")

    when (outcome) {
        TrackingLogic.ImplicitTriggerOutcome.DeepLinkTrigger -> {
            if (isPaywallPresented) {
                dismiss()
            }
            val presentationRequest = dependencyContainer.makePresentationRequest(
                presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation
            )
            internallyPresent(presentationRequest)
        }
        TrackingLogic.ImplicitTriggerOutcome.ClosePaywallThenTriggerPaywall -> {
            val lastPresentationItems = presentationItems.getLast() ?: return
            if (isPaywallPresented) {
                dismissForNextPaywall()
            }
            val presentationRequest = dependencyContainer.makePresentationRequest(
                presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation
            )
            internallyPresent(presentationRequest, lastPresentationItems.statePublisher)
        }
        TrackingLogic.ImplicitTriggerOutcome.TriggerPaywall -> {
            val presentationRequest = dependencyContainer.makePresentationRequest(
                presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation
            )
            internallyPresent(presentationRequest)
        }
        TrackingLogic.ImplicitTriggerOutcome.DisallowedEventAsTrigger -> {
            Logger.debug(
                logLevel = LogLevel.warn,
                scope = LogScope.superwallCore,
                message = "Event Used as Trigger",
                info = mapOf("message" to "You can't use events as triggers")
            )
        }
        TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall -> {
            return
        }
        TrackingLogic.ImplicitTriggerOutcome.DeepLinkTrigger -> {
            if (isPaywallPresented) {
                dismiss()
            }
            val presentationRequest = dependencyContainer.makePresentationRequest(
                presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation
            )
            internallyPresent(presentationRequest)
        }
        TrackingLogic.ImplicitTriggerOutcome.ClosePaywallThenTriggerPaywall -> {
            val lastPresentationItems = presentationItems.getLast() ?: return
            if (isPaywallPresented) {
                dismissForNextPaywall()
            }
            val presentationRequest = dependencyContainer.makePresentationRequest(
                presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation
            )
            internallyPresent(presentationRequest, lastPresentationItems.statePublisher)
        }
        TrackingLogic.ImplicitTriggerOutcome.TriggerPaywall -> {
            val presentationRequest = dependencyContainer.makePresentationRequest(
                presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation
            )
            internallyPresent(presentationRequest)
        }
    }
}

