package com.superwall.sdk.analytics.internal

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.paywall.presentation.dismissForNextPaywall
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.internallyPresent
import com.superwall.sdk.paywall.presentation.internal.operators.logErrors
import com.superwall.sdk.paywall.presentation.internal.operators.waitForSubsStatusAndConfig
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.utilities.withErrorTrackingAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

suspend fun Superwall.track(event: Trackable): Result<TrackingResult> {
    return withErrorTrackingAsync {
        // Wait for the SDK to be fully initialized
        Superwall.hasInitialized.first()

        // Get parameters to be sent to the delegate and stored in an event.
        // now with Date
        val eventCreatedAt = Date()
        val parameters =
            TrackingLogic.processParameters(
                trackableEvent = event,
                appSessionId = dependencyContainer.appSessionManager.appSession.id,
            )

        // For a trackable superwall event, send params to delegate
        if (event is TrackableSuperwallEvent) {
            val info =
                SuperwallEventInfo(
                    event = event.superwallEvent,
                    params = parameters.delegateParams,
                )

            dependencyContainer.delegateAdapter.handleSuperwallEvent(eventInfo = info)

            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.events,
                message = "Logged Event",
                info = parameters.audienceFilterParams,
            )
        }

        val eventData =
            EventData(
                name = event.rawName,
                parameters = parameters.audienceFilterParams,
                createdAt = eventCreatedAt,
            )

        // If config doesn't exist yet, we rely on previously saved feature flag
        // to determine whether to disable verbose events.
        val existingDisableVerboseEvents =
            dependencyContainer.configManager.config
                ?.featureFlags
                ?.disableVerboseEvents
        val previousDisableVerboseEvents = dependencyContainer.storage.read(DisableVerboseEvents)

        val verboseEvents = existingDisableVerboseEvents ?: previousDisableVerboseEvents

        if (TrackingLogic.isNotDisabledVerboseEvent(
                event = event,
                disableVerboseEvents = verboseEvents,
                isSandbox = dependencyContainer.makeIsSandbox(),
            )
        ) {
            dependencyContainer.eventsQueue.enqueue(
                data = eventData,
                event = event,
            )
        }
        dependencyContainer.storage.coreDataManager.saveEventData(eventData)

        if (event.canImplicitlyTriggerPaywall) {
            CoroutineScope(Dispatchers.IO).launch {
                Superwall.instance.handleImplicitTrigger(
                    event = event,
                    eventData = eventData,
                )
            }
        }

        return@withErrorTrackingAsync TrackingResult(
            data = eventData,
            parameters = parameters,
        )
    }.toResult()
}

suspend fun Superwall.handleImplicitTrigger(
    event: Trackable,
    eventData: EventData,
) = withContext(Dispatchers.Main) {
    serialTaskManager.addTask {
        internallyHandleImplicitTrigger(event, eventData)
    }
}

private suspend fun Superwall.internallyHandleImplicitTrigger(
    event: Trackable,
    eventData: EventData,
) = withContext(Dispatchers.Main) {
    return@withContext withErrorTrackingAsync {
        val presentationInfo = PresentationInfo.ImplicitTrigger(eventData)

        var request =
            dependencyContainer.makePresentationRequest(
                presentationInfo = presentationInfo,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation,
            )

        try {
            waitForSubsStatusAndConfig(request, null)
        } catch (e: Throwable) {
            logErrors(request, e)
            return@withErrorTrackingAsync
        }

        val outcome =
            TrackingLogic.canTriggerPaywall(
                event,
                dependencyContainer.configManager.triggersByEventName.keys
                    .toSet(),
                paywallView,
            )

        var statePublisher = MutableSharedFlow<PaywallState>()

        when (outcome) {
            TrackingLogic.ImplicitTriggerOutcome.DeepLinkTrigger -> {
                dismiss()
            }

            TrackingLogic.ImplicitTriggerOutcome.ClosePaywallThenTriggerPaywall -> {
                val lastPresentationItems = presentationItems.last ?: return@withErrorTrackingAsync
                dismissForNextPaywall()
                statePublisher = lastPresentationItems.statePublisher
            }

            TrackingLogic.ImplicitTriggerOutcome.TriggerPaywall -> {}
            TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall -> {
                return@withErrorTrackingAsync
            }

            else -> {}
        }

        request.flags.isPaywallPresented = isPaywallPresented

        internallyPresent(request, statePublisher)
    }
}
