package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationPipelineError
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow

// Defining the data class equivalent of the struct in Swift
data class TriggerResultResponsePipelineOutput(
    val triggerResult: TriggerResult,
    val debugInfo: Map<String, Any>,
    val confirmableAssignment: ConfirmableAssignment?,
    val experiment: Experiment
)

// Creating the extension function for Superwall
suspend fun Superwall.handleTriggerResult(
    request: PresentationRequest,
    input: AssignmentPipelineOutput,
    paywallStatePublisher: MutableSharedFlow<PaywallState>
): TriggerResultResponsePipelineOutput {
    var errorType: PaywallPresentationRequestStatusReason = PaywallPresentationRequestStatusReason.Unknown()

    when (val triggerResult = input.triggerResult) {
        is TriggerResult.Paywall -> {
            return TriggerResultResponsePipelineOutput(
                triggerResult = triggerResult,
                debugInfo = input.debugInfo,
                confirmableAssignment = input.confirmableAssignment,
                experiment = triggerResult.experiment
            )
        }
        is TriggerResult.Holdout -> {
            val sessionEventsManager = dependencyContainer.sessionEventsManager
            sessionEventsManager?.triggerSession?.activateSession(
                request.presentationInfo,
                request.presenter,
                triggerResult =  triggerResult
            )
            errorType = PaywallPresentationRequestStatusReason.Holdout(triggerResult.experiment)
            paywallStatePublisher.emit(PaywallState.Skipped(PaywallSkippedReason.Holdout(triggerResult.experiment)))
        }
        is TriggerResult.NoRuleMatch -> {
            val sessionEventsManager = dependencyContainer.sessionEventsManager
            sessionEventsManager?.triggerSession?.activateSession(
                request.presentationInfo,
                request.presenter,
                triggerResult= triggerResult
            )
            errorType = PaywallPresentationRequestStatusReason.NoRuleMatch()
            paywallStatePublisher.emit(PaywallState.Skipped(PaywallSkippedReason.NoRuleMatch()))
        }
        is TriggerResult.EventNotFound -> {
            errorType = PaywallPresentationRequestStatusReason.EventNotFound()
            paywallStatePublisher.emit(PaywallState.Skipped(PaywallSkippedReason.EventNotFound()))
        }
        is TriggerResult.Error -> {
            Logger.debug(
                LogLevel.error,
                LogScope.paywallPresentation,
                "Error Getting Paywall View Controller",
                input.debugInfo,
                triggerResult.error
            )
            errorType = PaywallPresentationRequestStatusReason.NoPaywallViewController()
            paywallStatePublisher.emit( PaywallState.PresentationError(triggerResult.error))
        }
    }

    // You might want to introduce a way to signal completion in your StateFlow
    // paywallStatePublisher.send(completion: .finished)
    paywallStatePublisher.emit(PaywallState.Finalized())
    throw errorType
}
